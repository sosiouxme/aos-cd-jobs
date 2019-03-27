#!/usr/bin/env groovy

buildlib = load("pipeline-scripts/buildlib.groovy")
commonlib = buildlib.commonlib

// Properties that should be initialized and not updated
version = [
    stream: "",     // "X.Y" e.g. "4.0"
    full: "",       // e.g. "4.0.0"
    release: "",    // e.g. "201901011200"
    major: 0,       // X in X.Y, e.g. 4
    minor: 0,       // Y in X.Y, e.g. 0
]
doozerWorking = "${env.WORKSPACE}/doozer_working" // must be in WORKSPACE to archive artifacts
doozerOpts = "--working-dir ${doozerWorking}"

// this plan is to be initialized but then adjusted for incremental builds
buildPlan = [
    dryRun: false, // report build plan without performing it
    forceBuild: false, // build regardless of whether source has changed
    buildRpms: false,
    rpmsIncluded: "", // comma-separated list
    rpmsExcluded: "", // comma-separated list
    puddleConf: "",  // URL to download the puddle conf
    buildImages: false,
    imagesIncluded: "", // comma-separated list
    imagesExcluded: "", // comma-separated list
]
// initialized but composeDir only filled in when puddle creates a compose
rpmMirror = [       // where to mirror RPM compose
    target: "",     // "online:int" or "pre-release"
    textTarget: "", // e.g. "
    composeDir: "", // e.g. "YYYY-MM-DD.1"
    url: "",
]

// some state to record and preserve between stages
finalRpmVersionRelease = ""  // set before building a compose

/**
 * Initialize properties from Jenkins parameters.
 * @return map which is the buildPlan property of this build.
*/
def initialize() {
    GITHUB_BASE = "git@github.com:openshift"  // buildlib uses this :eyeroll:

    currentBuild.displayName = "#${currentBuild.number} - ${params.BUILD_VERSION}.??"
    echo "Initializing build: ${currentBuild.displayName}"

    version.stream = params.BUILD_VERSION.trim()
    version << determineBuildVersion(version.stream)
    doozerOpts += " --group 'openshift-${version.stream}'"

    buildPlan << [
        dryRun: params.DRY_RUN,
        forceBuild: params.FORCE_BUILD,
        buildRpms: params.BUILD_RPMS != "none",
        buildImages: params.BUILD_IMAGES != "none",
    ]

    // determine whether the user wanted to specify includes or excludes
    rpmList = [only: "rpmsIncluded", except: "rpmsExcluded"][params.BUILD_RPMS]
    if (rpmList) {
        buildPlan[rpmList] = commonlib.cleanCommaList(params.RPM_LIST)
    } else if (params.RPM_LIST.trim()) {
        error("aborting because a list of RPMs was specified; you probably want to specify only/except.")
    }

    imageList = [only: "imagesIncluded", except: "imagesExcluded"][params.BUILD_IMAGES]
    if (imageList) {
        buildPlan[imageList] = commonlib.cleanCommaList(params.IMAGE_LIST)
    } else if (params.IMAGE_LIST.trim()) {
        error("aborting because a list of images was specified; you probably want to specify only/except.")
    }

    echo "Initial build plan: ${buildPlan}"

    // figure out the puddle configuration
    aosCdJobsCommitSha = commonlib.shell(script: "git rev-parse HEAD", returnStdout: true).trim()
    def puddleConfBase = "https://raw.githubusercontent.com/openshift/aos-cd-jobs/" +
                         "${aosCdJobsCommitSha}/build-scripts/puddle-conf"
    buildPlan.puddleConf = "${puddleConfBase}/atomic_openshift-${version.stream}.conf"
    // and where to mirror the compose when it's done
    rpmMirror << [
        target: params.RPM_MIRROR ?: "pre-release",
        textTarget: (params.RPM_MIRROR == "online:int") ? "(Integration Testing)" : "(Release Candidate)",
        url: getMirrorUrl(params.RPM_MIRROR, version.stream),
    ]

    // adjust the build "title"
    currentBuild.displayName = "#${currentBuild.number} - ${version.full}-${version.release}"
    if (buildPlan.dryRun) { currentBuild.displayName += " [DRY RUN]" }
    if (buildPlan.forceBuild) { currentBuild.displayName += " [force build]" }
    if (!buildPlan.buildRpms) { currentBuild.displayName += " [no RPMs]" }
    if (!buildPlan.buildImages) { currentBuild.displayName += " [no images]" }

    // get a fresh doozerWorking; removing the old one is left in the background.
    // NOTE: this waits for the background process if wrapped in commonlib.shell;
    // as this is designed to run instantly and never fail, just run it in a normal shell.
    sh """
        mkdir -p ${doozerWorking}
        mv ${doozerWorking} ${doozerWorking}.rm.${currentBuild.number}
        mkdir -p ${doozerWorking}
        # see discussion at https://stackoverflow.com/a/37161006 re:
        JENKINS_NODE_COOKIE=dontKill BUILD_ID=dontKill nohup bash -c 'rm -rf ${doozerWorking}.rm.*' &
    """
    return planBuilds()
}

def latestOpenshiftRpmBuild(stream) {
    retry(3) {
        commonlib.shell(
            script: "brew latest-build --quiet rhaos-${stream}-rhel-7-candidate openshift | awk '{print \$1}'",
            returnStdout: true,
        ).trim()
    }
}

// From a brew NVR of openshift, return just the V part.
@NonCPS
def extractBuildVersion(build) {
    // closure also keeps regex away from pipeline steps (error|echo)
    def match = build =~ /(?x) ^openshift- (  \d+  ( \. \d+ )+  )-/
    return match ? match[0][1] : "" // first group in the regex
}

/**
 * From the minor version (stream) and parameters, determine which version to build.
 * @param stream: OCP minor version "X.Y"
 * @return a map to merge into the "version" property representing the determined version
 */
def determineBuildVersion(stream) {
    def full = "${stream}.0"  // default
    def release = new Date().format("yyyyMMddHHmm")

    def prevBuild = latestOpenshiftRpmBuild(stream)
    if(params.NEW_VERSION.trim() == "+") {
        // increment previous build version
        full = extractBuildVersion(prevBuild)
        if (!full) { error("Could not determine version from last build '${prevBuild}'") }

        def segments = full.tokenize(".").collect { it.toInteger() }
        segments[-1]++
        full = segments.join(".")
        echo("Using version ${full} incremented from latest openshift package ${prevBuild}")
    } else if(params.NEW_VERSION) {
        // explicit version given
        full = commonlib.standardVersion(params.NEW_VERSION, false)
        echo("Using NEW_VERSION parameter for version: ${full}")
    } else if (prevBuild) {
        // use version from previous build
        full = extractBuildVersion(prevBuild)
        if (!full) { error("Could not determine version from last build '${prevBuild}'") }
        echo("Using version ${full} from latest openshift package ${prevBuild}")
    }

    if (! full.startsWith("${stream}.")) {
        // The version we came up with somehow doesn't match what we expect to build; abort
        error("Determined a version, '${full}', that does not begin with '${stream}.'")
    }

    stream = stream.tokenize('.').collect { it.toInteger() }
    return [
        major: stream[0],
        minor: stream[1],
        full: full,
        release: release,
    ]
}

/**
 * Plan what will be built.
 * Figure out whether we're building RPMs and/or images, and which ones, based on
 * the parameters and which sources have changed (if relevant).
 * Fills in the "buildPlan" property for later stages to use.
 * @return map which is the buildPlan property of this build.
 */
def planBuilds() {
    if (buildPlan.forceBuild) {
        echo "Building all as requested regardless of whether source has changed."
        currentBuild.description = "Building from source whether changed or not.\n"
        return buildPlan
    }

    // otherwise we need to scan sources.
    echo "Building only where source has changed."
    currentBuild.description = "Building sources that have changed.\n"

    def changed = [:]
    try {
        def yamlData = readYaml text: buildlib.doozer(
            """
            ${doozerOpts}
            ${includeExclude "rpms", buildPlan.rpmsIncluded, buildPlan.rpmsExcluded}
            ${includeExclude "images", buildPlan.imagesIncluded, buildPlan.imagesExcluded}
            config:scan-sources --yaml
            """, [capture: true]
        )
        changed = getChanges(yamlData)

        def report = { msg ->
            echo msg
            currentBuild.description += "${msg}\n"
        }
        if (!buildPlan.buildRpms) {
            report "RPMs: not building"
        } else if (changed.rpms) {
            report "RPMs: building " + changed.rpms.join(", ")
            buildPlan.rpmsIncluded = changed.rpms.join(",")
            buildPlan.rpmsExcluded = ""
        } else {
            report "RPMs: none changed"
            buildPlan.buildRpms = false
        }

        if (!buildPlan.buildImages) {
            report "Images: not building"
            return buildPlan
        } else if (!changed.images) {
            report "Images: none changed"
            buildPlan.buildImages = false
            currentBuild.displayName += " [no changes]"
            return buildPlan
        }
        report "Found ${changed.images.size()} image(s) with changes:\n  " + changed.images.join("\n  ")

        // also determine child images of changed
        yamlData = readYaml text: buildlib.doozer(
            """
            ${doozerOpts}
            ${includeExclude "images", buildPlan.imagesIncluded, buildPlan.imagesExcluded}
            images:show-tree --yml
            """, [capture: true]
        )

        // scan the image tree for changed and their children using recursive closure
        Closure gather_children  // needs to be defined separately to self-call
        gather_children = { all, data, initial, gather ->
            // all(list): all images gathered so far while traversing tree
            // data(map): the part of the yaml image tree we're looking at
            // initial(list): all images initially found to have changed
            // gather(bool): whether this is a subtree of an image with changed source
            data.each { image, children ->
                def gather_this = gather || image in initial
                if (gather_this) {  // this or an ancestor was a changed image
                    all.add(image)
                }
                // scan children recursively
                all = gather_children(all, children, initial, gather_this)
            }
            return all
        }
        def images = gather_children([], yamlData, changed.images, false)
        children = images - changed.images
        if (children) {
            report "Images: also building ${children.size()} child(ren):\n  " + children.join("\n  ")
        }
        buildPlan.imagesIncluded = images.join(",")
        buildPlan.imagesExcluded = ""

        // NOTE: it might be nice not to rebase child images where the source hasn't changed.
        // However we would still need to update dockerfile for those images; but running doozer
        // separately for rebase and update-dockerfile would mess up parent-child relationships,
        // and it isn't worth the trouble to make that work.
    } catch (err) {
        currentBuild.description += "error during plan builds step:\n${err.getMessage()}\n"
        throw err
    }
    return buildPlan
}

// extract the list of changed items of each kind
@NonCPS
def getChanges(yamlData) {
    def changed = ["rpms": [], "images": []]
    changed.each { kind, list ->
        yamlData[kind].each { 
            if (it["changed"]) {
                list.add(it["name"])
            }
        }
    }
    return changed
}

// determine what doozer parameter (if any) to use for includes/excludes
def includeExclude(kind, includes, excludes) {
    // --latest-parent-version only applies for images but won't hurt for RPMs
    if (includes) { return "--latest-parent-version --${kind} ${includes}" }
    if (excludes) { return "--latest-parent-version --${kind} '' --exclude ${excludes}" }
    return "--${kind} ''"
}

def stageBuildRpms() {
    if (!buildPlan.buildRpms) {
        echo "Not building RPMs."
        return
    }
    def cmd =
        """
        ${doozerOpts}
        ${includeExclude "rpms", buildPlan.rpmsIncluded, buildPlan.rpmsExcluded}
        rpms:build --version v${version.full}
        --release ${version.release}
        """

    buildPlan.dryRun ? echo("doozer ${cmd}") : buildlib.doozer(cmd)
}

/**
 * If necessary, puddle a new compose from our candidate tag.
 * Set the new puddle directory (e.g. "YYYY-MM-DD.1")
 */
def stageBuildCompose() {
    // we may or may not have (successfully) built the openshift RPM in this run.
    // in order to script the correct version to publish later, determine what's there now.
    finalRpmVersionRelease = latestOpenshiftRpmBuild(version.stream).replace("openshift-", "")

    if (!buildPlan.buildRpms && !buildPlan.forceBuild) {
        // a force build of just images is likely to want to pick up new dependencies,
        // so in that case still create the compose.
        echo "No RPMs built, not a force build; no need to create a compose"
        return
    }
    if(buildPlan.dryRun) {
        echo "Build puddle with conf ${buildPlan.puddleConf}"
        rpmMirror.composeDir = "YYYY-MM-DD.1"
        return
    }

    rpmMirror.composeDir = buildlib.build_puddle(
        buildPlan.puddleConf,    // The puddle configuration file to use
        null,   // signing key
        "-b",   // do not fail if we are missing dependencies
        "-d",   // print debug information
        "-n",   // do not send an email for this puddle
        "-s",   // do not create a "latest" link since this puddle is for building images
        "--label=building"   // create a symlink named "building" for the puddle
    )
}

def stageUpdateDistgit() {
    if (!buildPlan.buildImages) {
        echo "Not rebasing images."
        return
    }
    def cmd =
        """
        ${doozerOpts}
        ${includeExclude "images", buildPlan.imagesIncluded, buildPlan.imagesExcluded}
        images:rebase --version v${version.full} --release ${version.release}
        --message 'Updating Dockerfile version and release v${version.full}-${version.release}' --push
        """
    if(buildPlan.dryRun) {
        echo "doozer ${cmd}"
        return
    }
    buildlib.doozer(cmd)
    // TODO: if rebase fails for required images, notify image owners, and still notify on other reconciliations
    buildlib.notify_dockerfile_reconciliations(doozerWorking, version.stream)
    // TODO: if a non-required rebase fails, notify ART and the image owners
}

/**
 * Build the images according to plan.
 */
def stageBuildImages() {
    if (!buildPlan.buildImages) {
        echo "Not building images."
        return
    }
    try {
        def cmd =
            """
            ${doozerOpts}
            ${includeExclude "images", buildPlan.imagesIncluded, buildPlan.imagesExcluded}
            images:build
            --push-to-defaults --repo-type unsigned
            """
        if(buildPlan.dryRun) {
            echo "doozer ${cmd}"
            return
        }
        buildlib.doozer(cmd)
    }
    catch (err) {
        recordLog = buildlib.parse_record_log(doozerWorking)
        def failed_map = buildlib.get_failed_builds(recordLog, true)
        if (!failed_map) { throw err }  // failed so badly we don't know what failed; give up

        failed_images = failed_map.keySet()
        currentBuild.result = "UNSTABLE"
        currentBuild.description += "Failed images: ${failed_images.join(', ')}\n"

        def r = buildlib.determine_build_failure_ratio(recordLog)
        if (r.total > 10 && r.ratio > 0.25 || r.total > 1 && r.failed == r.total) {
            echo "${r.failed} of ${r.total} image builds failed; probably not the owners' fault, will not spam"
        } else {
            buildlib.mail_build_failure_owners(failed_map, "aos-team-art@redhat.com", params.MAIL_LIST_FAILURE)
        }
    }
}

def stageMirrorRpms() {
    if (!buildPlan.buildRpms && !buildPlan.forceBuild) {
        echo "No updated RPMs to mirror."
        return
    }

    def versionRelease = "${version.full}-${version.release}"

    // Push the building puddle out to the correct directory on the mirrors (e.g. online-int, online-stg, or enterprise-X.Y)
    def symlinkName = "latest"

    if(buildPlan.dryRun) {
        // this is silly, but we can't use *args to DRY -- see https://issues.jenkins-ci.org/browse/JENKINS-46163
        echo("invoke_on_rcm_guest push-to-mirrors.sh ${symlinkName} ${versionRelease} ${rpmMirror.target}")
    } else {
        buildlib.invoke_on_rcm_guest("push-to-mirrors.sh", symlinkName, versionRelease, rpmMirror.target)
    }

    def binaryPkgName = "openshift"
    if(buildPlan.dryRun) {
        echo("invoke_on_rcm_guest publish-oc-binary.sh ${rpmMirror.target} ${finalRpmVersionRelease} ${binaryPkgName}")
    } else {
        buildlib.invoke_on_rcm_guest("publish-oc-binary.sh", rpmMirror.target, finalRpmVersionRelease, binaryPkgName)
    }

    echo "Finished building OCP ${versionRelease}"
}

def stageSyncImages() {
    if (!buildPlan.buildImages) {
        echo "No built images to sync."
        return
    }
    if(buildPlan.dryRun) {
        echo "Not syncing images in a dry run."
        return
    }
    buildlib.sync_images(
        version.major,
        version.minor,
        "aos-team-art@redhat.com",
        currentBuild.number
    )
}

def getMirrorUrl(target, stream) {
    if (target == "online:int") {
        return "https://mirror.openshift.com/enterprise/online-int"
    }
    return "https://mirror.openshift.com/enterprise/enterprise-${stream}"
}

def stageReportSuccess() {
    def recordLog = buildPlan.dryRun ? [:] : buildlib.parse_record_log(doozerWorking)
    def timingReport = getBuildTimingReport(recordLog)
    currentBuild.description += timingReport

    def stateYaml = buildPlan.dryRun ? [:] : readYaml(file: "${doozerWorking}/state.yaml")
    messageSuccess(rpmMirror.url, rpmMirror.target)
    emailSuccess(rpmMirror.url, rpmMirror.target, timingReport, getImageBuildReport(recordLog), stateYaml)
}

def messageSuccess(mirrorURL, target) {
    if (!buildPlan.buildImages) {
        echo "No images built so no need for UMB message."
        return
    }
    try {
        timeout(3) {
            sendCIMessage(
                messageContent: "New build for OpenShift ${target}: ${version.full}",
                messageProperties:
                    """build_mode=${target}
                    puddle_url=${rpmMirror.url}/${rpmMirror.composeDir}
                    image_registry_root=registry.reg-aws.openshift.com:443
                    product=OpenShift Container Platform
                    """,
                messageType: 'ProductBuildDone',
                overrides: [topic: 'VirtualTopic.qe.ci.jenkins'],
                providerName: 'Red Hat UMB'
            )
        }
    } catch (mex) {
        echo "Error while sending CI message: ${mex.getMessage()}"
    }
}

def emailSuccess(mirrorURL, target, timingReport, imageList, stateYaml) {
    def mailList = params.MAIL_LIST_SUCCESS.trim()
    // note: the default for the parameter is empty.
    // normally this would probably only be set for full builds, if then.
    // but it's there in case you want to notify someone, perhaps yourself.
    if (!mailList) { return }

    def subjectTags = ""
    if (buildPlan.dryRun) { subjectTags += " [DRY RUN]" }

    if (buildPlan.buildImages) {
        if (buildPlan.imagesIncluded || buildPlan.imagesExcluded) {
            subjectTags += " [partial]"
        }
        def result = stateYaml.get("images:rebase", [:])
        if (result['success'] != result['total']) {
            currentBuild.displayName += " [rebase failure]"
            subjectTags += " [rebase failure]"
        }
        result = stateYaml.get("images:build", [:])
        if (result['success'] != result['total']) {
            currentBuild.displayName += " [image failure]"
            subjectTags += " [image failure]"
        }
    } else if (buildPlan.buildRpms) {
        subjectTags += " [RPM only]"
    } else {
        // no builds attempted; could only happen if incremental build found no changes
        subjectTags += " [no changes]"
    }

    def injectNotes = (params.SPECIAL_NOTES.trim() == "") ? "" :
"""
Special notes associated with this build:
*****************************************
${params.SPECIAL_NOTES.trim()}
*****************************************
"""

    def composeDetails = (!buildPlan.buildRpms && !buildPlan.forceBuild) ? "" :
"""\
RPMs:
    RPM Compose (internal): http://download-node-02.eng.bos.redhat.com/rcm-guest/puddles/RHAOS/AtomicOpenShift/${version.stream}/${rpmMirror.composeDir}
    External Mirror: ${mirrorURL}/${rpmMirror.composeDir}
"""

    def imageDetails = (!buildPlan.buildImages) ? "" :
"""\
${timingReport}
Images:
  - Images have been pushed to registry.reg-aws.openshift.com:443     (Get pull access [1])
    [1] https://github.com/openshift/ops-sop/blob/master/services/opsregistry.asciidoc#using-the-registry-manually-using-rh-sso-user
${imageList}
"""

    commonlib.email(
        to: mailList,
        from: "aos-team-art@redhat.com",
        subject: "[ART] New build for OCP ${target}: ${version.full}${subjectTags}",
        body:
"""\
Pipeline build "${currentBuild.displayName}" finished successfully.
${injectNotes}
The build description it finished with is:
******************************************
${currentBuild.description}\
******************************************

${composeDetails}\
${imageDetails}\
View the build artifacts and console output on Jenkins:
    - Jenkins job: ${env.BUILD_URL}
    - Console output: ${env.BUILD_URL}console
""");
}

// extract timing information from the recordLog and write a report string
// the timing record log entry has this form:
// image_build_metrics|elapsed_total_minutes={d}|task_count={d}|elapsed_wait_minutes={d}|
def getBuildTimingReport(recordLog) {
    metrics = recordLog['image_build_metrics']

    if (metrics == null || metrics.size() == 0) {
        return ""
    }

    return """
Images built: ${metrics[0]['task_count']}
Elapsed image build time: ${metrics[0]['elapsed_total_minutes']} minutes
Time spent waiting for OSBS capacity: ${metrics[0]['elapsed_wait_minutes']} minutes
"""
}

// get the list of images built
def getImageBuildReport(recordLog) {
    builds = recordLog['build']

    if ( builds == null ) {
        return ""
    }

    Set imageSet = []
    for (i = 0; i < builds.size(); i++) {
        bld = builds[i]
        if (bld['status'] == "0" && bld['push_status'] == "0") {
            imageSet << "${bld['image']}:${bld['version']}-${bld['release']}"
        }
    }

    return "\nImages included in build:\n    " +
        imageSet.toSorted().join("\n    ")
}

return this


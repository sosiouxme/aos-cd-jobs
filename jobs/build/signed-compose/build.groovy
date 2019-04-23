#!/usr/bin/env groovy

buildlib = load("pipeline-scripts/buildlib.groovy")
commonlib = buildlib.commonlib

// Properties that should not be updated after initialize()
workDir = "${env.WORKSPACE}/doozer_working" // must be in WORKSPACE to archive artifacts
cmdLineOpts = "--working-dir ${workDir}"
puddleconf = ""
mirrorUrl = ""
erratum = {}

// RPMdiffs completed before we added any builds
original_rpmdiffs = []
// any new builds attached to the erratum
changedBuilds = []

// record something in both console log and description
def report(msg) {
    echo msg
    currentBuild.description += "${msg}\n"
}

/**
 * Initialize properties from Jenkins parameters.
*/
def initialize() {
    buildlib.cleanWorkdir(workDir)

    currentBuild.displayName = "#${currentBuild.number} - ${params.OCP_BUILD_GROUP}"
    echo "Initializing build: ${currentBuild.displayName}"
    buildlib.shell "command -v elliott"
    buildlib.elliott "--version"

    cmdLineOpts += " --data-path ${params.OCP_BUILD_FORK} --group ${params.OCP_BUILD_GROUP}"

    // figure out the puddle configuration
    aosCdJobsCommitSha = commonlib.shell(script: "git rev-parse HEAD", returnStdout: true).trim()
    def puddleConfBase = "https://raw.githubusercontent.com/openshift/aos-cd-jobs/" +
                         "${aosCdJobsCommitSha}/build-scripts/puddle-conf"
    // this.puddleConf = "${puddleConfBase}/atomic_openshift-${version.stream}.conf"
    // and where to mirror the compose when it's done
    // this.mirrorUrl = "https://mirror.openshift.com/enterprise/enterprise-${stream TODO}"

    // adjust the build "title"
    if (params.OCP_BUILD_FORK != commonlib.defaultOcpBuildFork) {
        currentBuild.displayName += " [forked build data]"
    }

    // retrieve RPM errata (param or specified in group.yml)
    erratum = retrieveErratum()
    report "${erratum.id}: ${erratum.status} ${erratum.synopsis}"
}

def retrieveErratum() {
    return commonlib.withTmpFile { tmp ->
        def toRetrieve = params.OVERRIDE_ADVISORY ?
                         params.OVERRIDE_ADVISORY.toInteger() :
                         '--use-default-advisory rpm'
        commonlib.retryUntilTimeout(5) {
            buildlib.elliott "${cmdLineOpts} get ${toRetrieve} --json ${tmp}"
        }
        // XXX: readJSON and readFile inexplicably refuse to believe that tmp exists,
        // so use this silly workaround to read it instead.
        return readJSON(text: commonlib.sh(script: "cat ${tmp}", returnStdout: true))
    }
}

/**
 * ensure erratum is in the right state to add builds to.
 */
def prepareErratum() {
    // validate and record current erratum state (must be open and NEW_FILES or QE state)

    if ( !(erratum.status in ['NEW_FILES', 'QE']) ) {
        error("Advisory ${erratum.id} is in state ${erratum.status}; cannot add builds.")
    } else if (erratum.closed) {
        error("Advisory ${erratum.id} is closed; cannot add builds.")
    }

    if (erratum.status == 'QE') {
        report "Moving to NEW_FILES state to add builds."
        commonlib.retryUntilTimeout(5) {
            commonlib.elliott "${cmdLineOpts} change-state --state NEW_FILES ${erratum.id}"
        }
    }
}

def addBuilds() {
    // for each tag for this group, sweep RPMs into errata
    report "Adding RPM builds for default tag."
    def buildData = findAndAddBuilds()

    // TODO: scope this to those versions that have dual branches, currently all 4.x
    def altBase = buildData.base_tag.replace("rhel-7", "rhel-8") // TODO: specify in group.yml
    report "Adding RPM builds for alternate ${altBase} tag."
    changedBuilds = buildData.builds //nerf + findAndAddBuilds(altBase).builds

    if (changedBuilds) {
        report("Builds added to advisory:\n  " + changedBuilds.join("\n  "))
    } else {
        report "No new builds added to advisory."
    }
}

def findAndAddBuilds(branch="") {
    if (branch) { branch = "--branch ${branch}" }
    return commonlib.withTmpFile { tmp ->
        commonlib.retryUntilTimeout(10) {
            buildlib.elliott """
                ${cmdLineOpts}
                ${branch}
                find-builds --kind rpm --attach ${erratum.id}
                --json ${tmp}
            """
        }
        // XXX: readJSON and readFile inexplicably refuse to believe that tmp exists,
        // so use this silly workaround to read it instead.
        return readJSON(text: commonlib.sh(script: "cat ${tmp}", returnStdout: true))
    }
}

def completeRpmDiff() {
    if (!changedBuilds && rpmdiffsPassed(erratum)) {
        report "No new RPM builds and RPMdiffs already passed."
        return
    }
    report "Waiting for RPMdiffs to pass or fail."
    ensureNewRpmDiffs()
    waitForRpmDiffResult()
}

def ensureNewRpmDiffs() {
    if (changedBuilds) {
        // ensure new rpmdiffs are active and we're not looking at stale runs
        def ids = erratum.rpmdiffs.collect { it.id }
        try {
            commonlib.retryUntilTimeout(10) {
                def updated_ids = []
                try {
                    def updated = retrieveErratum()
                    updated_ids = updated.rpmdiffs.collect { it.id }
                } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
                    throw e
                } catch (e) {
                    sleep 30, SECONDS  // could be a transient; wait a bit to retry
                    throw(e)
                }
                if (ids.containsAll(updated_ids)) {
                    sleep 30, SECONDS
                    error("waiting for new RPMdiffs to be requested")
                }
            }
        } catch(e) {
            if (currentBuild.result == 'ABORTED') { throw e }
            waitForHumans("new RPMdiff requests not seen before timeout: ${e}")
        }
    }
}

def waitForRpmDiffResult() {
    // poll for rpmdiff to be complete or failed
    def result = null
    try {
        commonlib.retryUntilTimeout(60) {
            try {
                def updated = retrieveErratum()
                if (rpmdiffsFailed(updated)) {
                    result = 'failed'
                    return true
                }
                if (rpmdiffsPassed(updated)) {
                    result = 'passed'
                    return true
                }
            } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
                throw e
            } catch (e) {
                echo "Saw error retrieving advisory: ${e}"
            }
        }
    } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
        if (currentBuild.result == 'ABORTED') { throw e }
        echo "Timed out waiting for RPMdiff result"
    } catch (e) {
        echo "Encountered error waiting for RPMdiff result: ${e}"
    }

    if (result == 'passed') {
        report "RPMdiff passed or auto-waived everything."
    } else {
        waitForHumans("RPMdiff has not passed; please resolve and respond accordingly")
    }
}

def waitForHumans(message) {
    report "Input required: ${message}"
    commonlib.email(
        to: params.EMAIL_ALERT ?: 'aos-team-art@redhat.com',
        from: 'aos-team-art@redhat.com',
        subject: "Input required for build '${currentBuild.displayName}'",
        body: "${message}\n\n* ${env.BUILD_URL}console",
    )
    input(message: message)
}

@NonCPS
def rpmdiffsFailed(advisory) {
    return advisory.rpmdiffs.any { it.active && it.status in ['FAILED', 'INELIGIBLE'] }
}

@NonCPS
def rpmdiffsPassed(advisory) {
    return advisory.rpmdiffs.every { it.active && it.status in ['PASSED', 'WAIVED'] }
}

def signPackages() {

    if (changedBuilds || erratum.status == "QE") {
        // erratum.status is original state; put it back if it was QE before
        report "Moving advisory to QE state."
        commonlib.retryUntilTimeout(5) {
            commonlib.elliott "${cmdLineOpts} change-state --state QE ${advisory}"
        }
    } else {
        report "Advisory was in NEW_FILES and no new builds need signing."
        // TODO: figure out conditions where we need to sign/compose anyway
        return
    }

    // poll for signing to be done
    def rpmsSigned = false
    try {
        commonlib.retryUntilTimeout(60) {
            def advisory = retrieveErratum()
            if ('needs_sigs' in advisory.current_flags) {
                error("builds are not finished being signed yet.")
            }
            rpmsSigned = true
        }
    } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
        if (currentBuild.result == 'ABORTED') { throw e }
        echo "Timed out waiting for package signing to complete."
    } catch (e) {
        echo "Encountered error waiting for package signing to complete: ${e}"
    }

    // if failed: email humans and wait for proceed/abort input
    if (!rpmsSigned) {
        waitForHumans("RPMs not signed before timeout; please resolve and respond accordingly")
    }
}

def createCompose() {
    if (!changedBuilds && !params.FORCE_COMPOSE) {
        report "No builds changed; no need to rebuild compose."
        return
    }
    // build the signed compose(s) TODO
    report "Would have built the compose."
}

return this


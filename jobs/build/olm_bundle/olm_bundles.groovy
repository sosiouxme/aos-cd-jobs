buildlib = load('pipeline-scripts/buildlib.groovy')
commonlib = buildlib.commonlib
slacklib = commonlib.slacklib

doozer_working = "${WORKSPACE}/doozer_working"
buildlib.cleanWorkdir(doozer_working)

def doozer(cmd) {
    buildlib.doozer("--working-dir ${doozer_working} -g openshift-${params.BUILD_VERSION} ${cmd}", [capture: true])
}

def elliott(cmd) {
    buildlib.elliott("-g openshift-${params.BUILD_VERSION} ${cmd}", [capture: true])
}

/*
 * Return the brew package name of all OLM operators present in <params.BUILD_VERSION>
 */
def get_olm_operators() {
    doozer('olm-bundle:list-olm-operators').split("\n")
}

/*
 * Make sure 2 advisories belong to the same group that is given
 */
def validate_advisories(advisory1, advisory2, group) {
    def advisory1_v = elliott("get ${advisory1}")
    def advisory2_v = elliott("get ${advisory2}")
    if !(advisory1_v in advisory2_v && group in advisory2_v) {
        echo(advisory1_v, advisory2_v, group)
        error("Inconsistent advisories/group")
    }
}

/*
 * Return list of OLM Operator NVRs attached to given <advisory>
 */
def get_builds_from_advisory(advisory) {
    def json = elliott("get --json - ${advisory}")
    readJSON(text: json).errata_builds.values().flatten()
}

/*
 * Return the latest build of given <packages> for <tag>
 */
def get_latest_builds(packages) {
    if (!packages) { return [] }
    def tag = "rhaos-${params.BUILD_VERSION}-rhel-8-candidate"
    commonlib.shell(
        script: "brew --quiet latest-build ${tag} ${packages.join(' ')}",
        returnAll: true
    ).stdout.split("\n").collect { it.split(" ")[0] }
}

/*
 * Build corresponding bundle containers for given <operator_nvrs>
 * Return a list of built <bundle_nvrs>
 */
def build_bundles(operator_nvrs) {
    doozer("olm-bundle:rebase-and-build ${operator_nvrs.join(' ')} ${params.FORCE_BUILD ? '--force' : ''}").split("\n")
}

/*
 * Attach given <bundle_nvrs> to <advisory>
 */
def attach_bundles_to_advisory(bundle_nvrs, advisory) {
    elliott("change-state -s NEW_FILES -a ${advisory} ${params.DRY_RUN ? '--noop' : ''}")
    flags = bundle_nvrs.collect { "--build ${it}" }.join(' ')
    elliott("""find-builds -k image ${flags} ${params.DRY_RUN ? '' : "--attach ${advisory}"}""")
    sleep(30) // wait for ET validations to be complete
    elliott("change-state -s QE -a ${advisory} ${params.DRY_RUN ? '--noop' : ''}")
}

/*
 * Archive artifacts of possible interest from the doozer working directory.
 */
def archiveDoozerArtifacts() {
    commonlib.safeArchiveArtifacts([
        "doozer_working/*.log",
        "doozer_working/*.yaml",
        "doozer_working/brew-logs/**",
    ])
}

return this

#!/usr/bin/env groovy

node {
    checkout scm
    def buildlib = load("pipeline-scripts/buildlib.groovy")

    echo buildlib.rhcosReleaseStreamUrl("4.6", "aarch64")
    echo """${buildlib.orderedRhcosBuilds("4.6", "s390x")}"""
    echo """${buildlib.orderedRhcosBuilds("4.2", "x86_64")}"""
    try {
        echo """${buildlib.orderedRhcosBuilds("4.2", "aarch64")}"""
    } catch (exc) {
        echo "that was expected to fail"
    }

    echo """${buildlib.machineOsContentBuild("4.6", "ppc64le", true)}"""
    try {
        echo """${buildlib.machineOsContentBuild("4.6", "aarch64", false)}"""
    } catch (exc) {
        echo "that was expected to fail"
    }

    echo """${buildlib.latestRhcosPullspec("4.6", "s390x")}"""
    echo """${buildlib.scanForRhcosChanges("4.3")}"""
    echo """${buildlib.scanForRhcosChanges("4.2")}"""
}

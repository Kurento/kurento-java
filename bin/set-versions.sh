#!/usr/bin/env bash

#/ Version change helper.
#/
#/ This shell script will traverse all subprojects and change their
#/ versions to the one provided as argument.
#/
#/
#/ Arguments
#/ =========
#/
#/ <Version>
#/
#/   Base version number to set. When '--release' is used, this version will
#/   be used as-is; otherwise, a nightly/snapshot indicator will be appended.
#/
#/   <Version> should be in a format compatible with Semantic Versioning,
#/   such as "1.2.3" or, in general terms, "<Major>.<Minor>.<Patch>".
#/
#/ --release
#/
#/   Use version numbers intended for Release builds, such as "1.2.3". If this
#/   option is not given, a nightly/snapshot indicator is appended: "-dev".
#/
#/   Optional. Default: Disabled.
#/
#/ --git-add
#/
#/   Add changes to the Git stage area. Useful to leave everything ready for a
#/   commit.
#/
#/   Optional. Default: Disabled.



# Shell setup
# ===========

# Bash options for strict error checking.
set -o errexit -o errtrace -o pipefail -o nounset

# Check dependencies.
command -v mvn >/dev/null || {
    echo "ERROR: 'mvn' is not installed; please install it"
    exit 1
}
command -v xmlstarlet >/dev/null || {
    log "ERROR: 'xmlstarlet' is not installed; please install it"
    exit 1
}

# Trace all commands.
set -o xtrace



# Parse call arguments
# ====================

CFG_VERSION=""
CFG_RELEASE="false"
CFG_GIT_ADD="false"

while [[ $# -gt 0 ]]; do
    case "${1-}" in
        --release)
            CFG_RELEASE="true"
            ;;
        --git-add)
            CFG_GIT_ADD="true"
            ;;
        *)
            CFG_VERSION="$1"
            ;;
    esac
    shift
done



# Config restrictions
# ===================

if [[ -z "$CFG_VERSION" ]]; then
    echo "ERROR: Missing <Version>"
    exit 1
fi

REGEX='^[[:digit:]]+\.[[:digit:]]+\.[[:digit:]]+$'
[[ "$CFG_VERSION" =~ $REGEX ]] || {
    echo "ERROR: '$CFG_VERSION' must be compatible with Semantic Versioning: <Major>.<Minor>.<Patch>"
    exit 1
}

echo "CFG_VERSION=$CFG_VERSION"
echo "CFG_RELEASE=$CFG_RELEASE"
echo "CFG_GIT_ADD=$CFG_GIT_ADD"



# Internal variables
# ==================

if [[ "$CFG_RELEASE" == "true" ]]; then
    VERSION="$CFG_VERSION"
else
    VERSION="${CFG_VERSION}-SNAPSHOT"
fi



# Helper functions
# ================

# Add the given file(s) to the Git stage area.
function git_add() {
    [[ $# -ge 1 ]] || {
        echo "ERROR [git_add]: Missing argument(s): <file1> [<file2> ...]"
        return 1
    }

    if [[ "$CFG_GIT_ADD" == "true" ]]; then
        git add -- "$@"
    fi
}



# Apply version
# =============

if [[ "$CFG_RELEASE" == "true" ]]; then
    MVN_ALLOW_SNAPSHOTS="false"
else
    MVN_ALLOW_SNAPSHOTS="true"
fi

{
    pushd kurento-parent-pom/

    # Parent version: Update to latest available.
    mvn versions:update-parent \
        -DgenerateBackupPoms=false \
        -DallowSnapshots="$MVN_ALLOW_SNAPSHOTS"

    # Project version: Set new version.
    mvn versions:set \
        -DgenerateBackupPoms=false \
        -DallowSnapshots="$MVN_ALLOW_SNAPSHOTS" \
        -DnewVersion="$VERSION"

    # API dependencies: Set new version.
    MODULES=(
        kms-api-core
        kms-api-elements
        kms-api-filters
    )
    for MODULE in "${MODULES[@]}"; do
        mvn versions:set-property \
            -DgenerateBackupPoms=false \
            -Dproperty="version.${MODULE}" \
            -DnewVersion="$VERSION"
    done

    # Install the project into local cache.
    # This allows the following project(s) to update their parent.
    mvn clean install -Dmaven.test.skip=true

    popd
}

{
    # Parent version: Update to latest available.
    #
    # Cannot use the "versions:update-parent" plugin here, because it requires
    # that the *current* parent version also exists. So if won't allow
    # updating from a nonexistent version (like "1.2.3-SNAPSHOT").
    #
    # mvn versions:update-parent \
    #     -DgenerateBackupPoms=false \
    #     -DallowSnapshots="$MVN_ALLOW_SNAPSHOTS" \
    #     -DparentVersion="[$VERSION]"
    #
    xmlstarlet edit -S --inplace \
        --update "/_:project/_:parent/_:version" \
        --value "$VERSION" \
        pom.xml

    # Project version: Inherited from parent.

    # Children versions: Make them inherit from parent.
    #
    # Cannot use the "versions:update-parent" plugin here, because it requires
    # that the *current* parent version also exists. So if won't allow
    # updating from a nonexistent version (like "1.2.3-SNAPSHOT").
    #
    # mvn versions:update-child-modules \
    #     -DgenerateBackupPoms=false \
    #     -DallowSnapshots="$MVN_ALLOW_SNAPSHOTS"
    #
    CHILDREN=(
        kurento-assembly
        kurento-basicroom
        kurento-client
        kurento-commons
        kurento-integration-tests
        kurento-jsonrpc
        kurento-parent-pom
        kurento-repository
    )
    for CHILD in "${CHILDREN[@]}"; do
        find "$CHILD" -name pom.xml -print0 | xargs -0 -n1 \
            xmlstarlet edit -S --inplace \
                --update "/_:project/_:parent/_:version" \
                --value "$VERSION"
    done
}

git_add \
    '*pom.xml'

echo "Done!"

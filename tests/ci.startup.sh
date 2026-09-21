#!/bin/bash

source ./set-env.sh

echo " == Printing the most important environment variables"
echo " MANIFEST: ${MANIFEST}"
echo " TESTS_IMAGE: ${TESTS_IMAGE}"
echo " JAHIA_IMAGE: ${JAHIA_IMAGE}"
echo " MODULE_ID: ${MODULE_ID}"
echo " JAHIA_URL: ${JAHIA_URL}"
echo " SUPER_USER_PASSWORD: ${SUPER_USER_PASSWORD}"

version=$(node -p "require('./package.json').devDependencies['@jahia/cypress']")
echo Using @jahia/cypress@$version...
# Forward arguments: the upstream CLI takes `notests` to boot Jahia without running the suite,
# which is the documented local-debug path. Without "$@" that argument is silently dropped and
# `./ci.startup.sh notests` runs the whole suite anyway.
npx --yes --package @jahia/cypress@$version ci.startup "$@"

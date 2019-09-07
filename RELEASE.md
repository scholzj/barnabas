# Release

`make release` target can be used to create a release. Environment variable `RELEASE_VERSION` (default value `latest`) can be used to define the release version. The `release` target will: 
* Update all tags of Docker images to `RELEASE_VERSION` 
* Update documentation version to `RELEASE_VERSION` 
* Set version of the main Maven projects (`topic-operator` and `cluster-operator`) to `RELEASE_VERSION` 
* Create TAR.GZ and ZIP archives with the Kubernetes and OpenShift YAML files which can be used for deployment and documentation in HTML format.
 
The `release` target will not build the Docker images - they should be built and pushed automatically by Travis CI when the release is tagged in the GitHub repository. It also doesn't deploy the Java artifacts anywhere. They are only used to create the Docker images.

The release process should normally look like this:
1. Create a release branch
2. Export the desired version into the environment variable `RELEASE_VERSION`
3. Run `make clean release`
4. Commit the changes to the existing files (do not add the newly created top level TAR.GZ, ZIP archives or .yaml files into Git)
5. Push the changes to the release branch on GitHub
6. Create the tag and push it to GitHub. Tag name determines the tag of the resulting Docker images. Therefore the Git tag name has to be the same as the `RELEASE_VERSION`, i.e. `git tag ${RELEASE_VERSION}`,
7. Once the CI build for the tag is finished and the Docker images are pushed to Docker Hub, Create a GitHub release and tag based on the release branch. Attach the TAR.GZ/ZIP archives, YAML files (for installation from URL) from step 4 and the Helm Chart to the release
8. On the `master` git branch
  * Update the versions to the next SNAPSHOT version using the `next_version` `make` target. For example to update the next version to `0.6.0-SNAPSHOT` run: `make NEXT_VERSION=0.6.0-SNAPSHOT next_version`.
  * Copy the `helm-charts/index.yaml` from the `release` branch to `master`.
9. Update the website
  * Add release documentation to `strimzi.github.io/docs/`. Update references to docs in `strimzi.github.io/documentation/index.md` and `strimzi.github.io/documentation/archive/index.md`. Update also the link from the start page: `strimzi.github.io/index.md`.
  * Update the Helm Chart repository file by copying `strimzi-kafka-operator/helm-charts/index.yaml` to `strimzi.github.io/charts/index.yaml`.
  * Update the Quickstarts for OKD and Minikube to use the latest stuff.
10. The maven artifacts (`api` module) will be automatically staged from TravisCI during the tag build. It has to be releases from [Sonatype](https://oss.sonatype.org/#stagingRepositories) to get to the main Maven repositories.
11. Update the Strimzi manifest files in Operate Hub [community operators](https://github.com/operator-framework/community-operators) repository and submit a pull request upstream. *Note*: Instructions for this step need updating.


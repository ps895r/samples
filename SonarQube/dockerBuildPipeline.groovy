import dst.PipelineDefaults

//Possible Parameters Include
// * dockerfile            Path to the Docker file relative to the repository root.  Default Dockerfile
// * dockerBuildContextDir The build context directory for Docker builds.  Default to '.' i.e. workspace root
// * dockerArguments       Additional arguments to pass to the build of the Docker application
// * dockerBuildTarget     Target to build when building the Docker application. Defaults to unset
// * masterBranch          Branch to consider as master, only this branch will receive the latest tag.  Default master
// * repository            Docker repository name to use
// * imagePrefix           Docker image name prefix
// * app                   Docker image name suffix
// * name                  Name of the Docker image used to add metadata to the image
// * description           Description of the Docker image used to add metadata to the image
// * receiveEvent          String Array: An array of event names that trigger the job
// * sendEvents            String Array: An array of event names that trigger down stream jobs
// * cronTrigger           String: Jenkins cron syntax specifying how often to trigger a build, only applies to your configured master branch.  Defaults to empty string i.e. no cron trigger
// * postBuildScript       String: Name of script to be executed in the post build stage
// * integrationScript     String: Name of script to be executed in the PR integration stage
// * buildPrepScript       String: Name of script to be executed in the build prep stage
// * unitTestScript        String: Name of script to be executed in the unit test stage
// * lintScript            String: Name of script to be executed in the lint stage
// * coverageScript        String: Name of script to be executed in the coverage stage
// * staticScript          String: Name of script to be executed in static stage
// * publishTestImage      Boolean: determines whether to publish test docker image
// * allBranches           Boolean: determines whether event triggers trigger all branches or only master
// * slackNotification     Array: ["<slack_channel>", "<jenkins_credential_id", <notify_on_start>, <notify_on_success>, <notify_on_failure>, <notify_on_fixed>]]
// * product               String: set product for the transfer function
// * targetOS              String: set targetOS for the transfer function
// * enableSonar           Boolean: determines whether to run SonarQube stages
// * sonarPrepScript       String: Name of script to be executed in the sonar prep stage
// * sonarScanJava         Boolean: Flag that can be set to scan Java files or skip
// * includeCharts         Boolean: Whether or not to include detecting, building, and publishing of charts in this pipeline (default = true)
// * disruptiveLevel       String: The disruptive level when updating, defaults to non-disruptive
// * helmSDPManifestConfig String: Name of the yaml config file used when generating helm SDP manifest file
// * autoJira              Boolean: Whether or not to create/update Jira issues when jobs fail on master or release branches
// * jiraProjectOverride   String: Project to use for auto-Jira tickets on failure
// * buildAgent            String: Name of build server to run on. Defaults to logic based on BRANCH_NAME.
// * versionScript         String: Name of the bash script to set the version of the release distribution
// * timeout               Positive Integer: job timeout in minutes. Defaults to 60
// * sshAgent              String: credential id that contains the ssh key to be passed to the --ssh flag during docker build

// The build parameters are now maintained in the PipelineDefaults.groovy file in src/dst

def call(body) {
    // Jenkins library for building docker files
    // Copyright 2018 Cray Inc. All rights reserved.

    ////////////////////////////////////////// JENKINS SHARED LIBRARY //////////////////////////////////////////

    // Library repo: https://github.hpe.com/hpe/hpc-dst-jenkins-shared-library.git

    // Transfers the build artifact(s) to iyumcf's DST yum repository
    // If the branch is master: /var/www/html/dstrepo/dev
    // If the branch isn't master: /var/www/html/dstrepo/predev

    //////////////////////////////////////// END JENKINS SHARED LIBRARY ////////////////////////////////////////

    echo 'Log Stash: dockerBuildPipeline'
    def pipeline_defaults = new PipelineDefaults()
    def pipelineParams = pipeline_defaults.dockerDefaults()
    def sonarParams = pipeline_defaults.sonarDefaults()

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = sonarParams
    body()
    
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    // Use this BUILD_DATE Variable instead of using an environment variable - dst-4032
    def buildDate = new Date().format( 'yyyyMMddHHmmss', TimeZone.getTimeZone('UTC') )

    // Variable to check whether to skip the 'success' post section
    def skipSuccess = false

    // Variable to decide whether to skip the slackNotify steps if the plugin isn't installed
    def skipSlack = slackNotify(exceptionCatch: true)

    // Slack notification of starting the Jenkins job if enabled
    if ((pipelineParams.slackNotification[2] != false && skipSlack != true)) {
        slackNotify(channel: "${pipelineParams.slackNotification[0]}",
                    credential: "${pipelineParams.slackNotification[1]}",
                    color: 'good',
                    message: "Starting: ${env.JOB_NAME} | Build URL: ${env.BUILD_URL}")
    }

    // Use app name as image name, prepend it with imagePrefix parameter if specified.
    def imageName = "${pipelineParams.app}"
    if (pipelineParams.imagePrefix != '') {
        imageName = "${pipelineParams.imagePrefix}-${pipelineParams.app}"
    }

    if (pipelineParams.buildAgent != '') {
        buildAgent = "${pipelineParams.buildAgent}"
    } else {
        buildAgent = 'docker'
    }

    pipeline {
        agent { node { label buildAgent } }

        triggers { eventTrigger jmespathQuery(
                                    getEventTriggerQuery(
                                        pipelineParams.receiveEvent,
                                        pipelineParams.allBranches,
                                        pipelineParams.masterBranch
                                    )
                                )

            // Cron Build Trigger (DST-7096)
            // This uses the Jenkins Cron syntax per https://www.jenkins.io/doc/book/pipeline/syntax/#cron-syntax
            // Only enabled if on the configured master branch for the build
            cron(
                selectCronTrigger(
                    branch: "${BRANCH_NAME}",
                    masterBranch: pipelineParams.masterBranch,
                    cronTrigger: pipelineParams.cronTrigger
                )
            )
        }
        // Configuration options applicable to the entire job
        options {
            // This build should not take long, fail the build if it appears stuck
            timeout(time: getDockerBuildTimeout(pipelineParams.timeout), unit: 'MINUTES')

            // Don't fill up the build server with unnecessary cruft
            buildDiscarder(logRotator(numToKeepStr: '10'))

            // Disable concurrent builds to minimize build collisions
            disableConcurrentBuilds()

            // Add timestamps and color to console output, cuz pretty
            timestamps()
        }

        environment {
            GIT_TAG = sh(returnStdout: true, script: 'git rev-parse --short=12 HEAD').trim()
            GIT_REPO_NAME = sh(returnStdout: true, script: "basename -s .git ${GIT_URL}").trim()
            BUILD_DATE = "${buildDate}"
            IMAGE_NAME = "${imageName}"
            IMAGE_LATEST = getDockerImageReference(repository: "${pipelineParams.repository}",
                                                   imageName: "${IMAGE_NAME}",
                                                   imageTag: 'latest',
                                                   product: "${pipelineParams.product}")
            IYUM_REPO_MAIN_BRANCH = "${pipelineParams.masterBranch}"
            TEST_IMAGE_LATEST = getDockerImageReference(repository: "${pipelineParams.repository}",
                                                        imageName: "${IMAGE_NAME}-test",
                                                        imageTag: 'latest',
                                                        product: "${pipelineParams.product}")
            EGREPTESTING = sh(script: "egrep \"^FROM [a-zA-Z0-9:_./\\-]*( [Aa][Ss] testing\$)\" ${pipelineParams.dockerfile}", returnStatus: true)
            EGREPLINT = sh(script: "egrep \"^FROM [a-zA-Z0-9:_./\\-]*( [Aa][Ss] lint\$)\" ${pipelineParams.dockerfile}", returnStatus: true)
            EGREPCOVERAGE = sh(script: "egrep \"^FROM [a-zA-Z0-9:_./\\-]*( [Aa][Ss] coverage\$)\" ${pipelineParams.dockerfile}", returnStatus: true)
            EGREPCODESTYLE = sh(script: "egrep \"^FROM [a-zA-Z0-9:_./\\-]*( [Aa][Ss] codestyle\$)\" ${pipelineParams.dockerfile}", returnStatus: true)
            USEENTRYPOINTFORTEST = "${pipelineParams.useEntryPointForTest}"
            MASTER_BRANCH = "${pipelineParams.masterBranch}"
            PARENT_BRANCH = setParentBranch("${pipelineParams.masterBranch}")
            PRODUCT = "${pipelineParams.product}"
            TARGET_OS = "${pipelineParams.targetOS}"
            TARGET_ARCH = 'noarch'
            ART_COMMON_CREDS = credentials('artifactory-login')
            HPE_ARTIFACTORY = credentials('artifact-server')
            HPE_GITHUB_TOKEN = credentials('ghe_jenkins_token')
            SONAR_TOKEN = credentials('sonar2')
        }
        stages {
            stage('Clean Checkout') {
                steps {
                    deleteDir()
                    checkout scm
                }
            }
            stage('Set Version') {
                steps {
                    script {
                        if (pipelineParams.versionScript != '' && fileExists("${pipelineParams.versionScript}")) {
                            env.VERSION = sh(returnStdout: true, script: """
                                chmod +x ${pipelineParams.versionScript}
                                ./${pipelineParams.versionScript}
                            """).trim()
                        } else if (fileExists('.version')) {
                            env.VERSION = sh(returnStdout: true, script: 'cat .version').trim()
                        } else {
                            error "Version script doesn't exist"
                        }
                        env.IMAGE_TAG = getDockerImageTag(version: "${VERSION}",
                                                          buildDate: "${BUILD_DATE}",
                                                          gitTag: "${GIT_TAG}",
                                                          gitBranch: "${GIT_BRANCH}")
                        env.IMAGE_VERSIONED = getDockerImageReference(repository: "${pipelineParams.repository}",
                                                                      imageName: "${IMAGE_NAME}",
                                                                      imageTag: "${IMAGE_TAG}",
                                                                      product: "${pipelineParams.product}")
                        env.TEST_IMAGE_VERSIONED = getDockerImageReference(repository: "${pipelineParams.repository}",
                                                                           imageName: "${IMAGE_NAME}-test",
                                                                           imageTag: "${IMAGE_TAG}",
                                                                           product: "${pipelineParams.product}")
                    }
                }
            }
            stage('Print Build Info') {
                steps {
                    printBuildInfo(pipelineParams)
                    script {
                        env.CHART_VERSION_ENV = ''
                        // Print the environment
                        sh 'env | sort'
                    }
                }
            }
            stage('Workdir Preparation') {
                steps {
                    sh 'mkdir -p build'
                    echo "Docker image tag for this build is ${IMAGE_TAG}"
                    echo "Docker image reference for this build is ${IMAGE_VERSIONED}"
                }
            }
            stage('Linting Jenkinsfile') {
                steps {
                    copyFiles('jenkinsfile_linter.py')
                    script {
                        withCredentials(
                            [usernamePassword(
                                credentialsId: 'dst-group-token',
                                passwordVariable: 'pass',
                                usernameVariable: 'user')]) 
                        {
                            def script_result = sh(
                                    returnStatus: true,
                                    script: "python3 jenkinsfile_linter.py --password $pass"
                                )

                            if (script_result != 0) {
                                currentBuild.result = 'FAILED'
                                error('Job FAILED Due To An Error In The Jenkinsfile Used')
                            }
                        }
                    }
                    sh 'rm -rf jenkinsfile_linter.py output.xml'
                }
            }
            stage('Build Preparation') {
                when {
                    expression {
                        return fileExists("${pipelineParams.buildPrepScript }") == true
                    }
                }
                environment {
                    ART_USER = "${env.ART_COMMON_CREDS_USR}"
                    ART_PASS = "${env.ART_COMMON_CREDS_PSW}"
                }
                steps {
                    echo 'Log Stash: dockerBuildPipeline - Build Preparation'
                    echo 'Build Prep'
                    sh "./${pipelineParams.buildPrepScript};"
                }
            }
            stage('Check Docker File') {
                steps {
                    sh """
                        if [ ! -f ${pipelineParams.dockerfile} ]; then
                            echo \"ERROR: ${pipelineParams.dockerfile} does not exist\"
                            exit 1;
                        fi
                    """
                }
            }
            // Run Unit Tests
            stage('Unit Tests') {
                when {
                    expression {
                        env.EGREPTESTING == '0' || fileExists("${pipelineParams.unitTestScript }") == true
                    }
                }
                steps {
                    echo 'Log Stash: dockerBuildPipeline - Unit Tests'
                    sh "if [ -f ${pipelineParams.unitTestScript} ]; then ./${pipelineParams.unitTestScript}; fi"
                    script {
                        if ( env.EGREPTESTING == '0' ) {
                            lazyDocker(imageName: env.IMAGE_VERSIONED,
                                       buildTarget: 'testing',
                                       dockerfile: "${pipelineParams.dockerfile}",
                                       dockerBuildContextDir: "${pipelineParams.dockerBuildContextDir}",
                                       masterBranch: "${pipelineParams.masterBranch}")
                        }
                    }
                }
            }
            stage('Analysis') {
                parallel {
                    stage('lint') {
                        when {
                            expression {
                                env.EGREPLINT == '0' || fileExists("${pipelineParams.lintScript }") == true
                            }
                        }
                        steps {
                            echo 'Log Stash: dockerBuildPipeline - lint'
                            sh "if [ -f ${pipelineParams.lintScript} ]; then ./${pipelineParams.lintScript}; fi"
                            script {
                                if ( env.EGREPLINT == '0' ) {
                                    lazyDocker(imageName: env.IMAGE_VERSIONED,
                                               buildTarget: 'lint',
                                               dockerfile: "${pipelineParams.dockerfile}",
                                               dockerBuildContextDir: "${pipelineParams.dockerBuildContextDir}",
                                               masterBranch: "${pipelineParams.masterBranch}")
                                }
                            }
                        }
                    }
                    stage('coverage') {
                        when {
                            expression {
                                env.EGREPCOVERAGE == '0' || fileExists("${pipelineParams.coverageScript }") == true
                            }
                        }
                        steps {
                            echo 'Log Stash: dockerBuildPipeline - coverage'
                            sh "if [ -f ${pipelineParams.coverageScript} ]; then ./${pipelineParams.coverageScript}; fi"
                            script {
                                if ( env.EGREPCOVERAGE == '0' ) {
                                    lazyDocker(imageName: env.IMAGE_VERSIONED,
                                               buildTarget: 'coverage',
                                               dockerfile: "${pipelineParams.dockerfile}",
                                               dockerBuildContextDir: "${pipelineParams.dockerBuildContextDir}",
                                               masterBranch: "${pipelineParams.masterBranch}")
                                }
                            }
                        }
                    }
                    stage('static') {
                        when {
                            expression {
                                env.EGREPCODESTYLE == '0' || fileExists("${pipelineParams.staticScript }") == true
                            }
                        }
                        steps {
                            echo 'Log Stash: dockerBuildPipeline - static'
                            sh "if [ -f ${pipelineParams.staticScript} ]; then ./${pipelineParams.staticScript}; fi"
                            script {
                                if ( env.EGREPCODESTYLE == '0' ) {
                                    lazyDocker(imageName: env.IMAGE_VERSIONED,
                                               buildTarget: 'codestyle',
                                               dockerfile: "${pipelineParams.dockerfile}",
                                               dockerBuildContextDir: "${pipelineParams.dockerBuildContextDir}",
                                               masterBranch: "${pipelineParams.masterBranch}")
                                }
                            }
                        }
                    }
                }
            }
            stage('stash build results') {
                when {
                    expression {
                        return sonarParams.enableSonar == true && dirExists(path: 'build/results') == true 
                    }
                }
                steps {
                    sh """
                        pwd
                        cd ${WORKSPACE}/build/results && tar -zcvf ${WORKSPACE}/buildResults.tar.gz .
                    """
                    stash name: 'buildResults', includes: 'buildResults.tar.gz'
                    script {
                        sonarParams.sonarResults = true
                    }
                }
            }
            stage('SonarQube') {
                steps {
                    script {
                        sonarScan(sonarParams)
                    }
                }
            }
            // Generate docker image
            stage('Build') {
                parallel {
                    stage('Image') {
                        steps {
                            labelAndBuildDockerImage(name: "${pipelineParams.name}",
                                                     description: "${pipelineParams.description}",
                                                     dockerfile: "${pipelineParams.dockerfile}",
                                                     dockerBuildContextDir: "${pipelineParams.dockerBuildContextDir}",
                                                     dockerArguments: "${pipelineParams.dockerArguments}",
                                                     dockerBuildTarget: "${pipelineParams.dockerBuildTarget}",
                                                     imageReference: "${IMAGE_VERSIONED}",
                                                     masterBranch: "${pipelineParams.masterBranch}",
                                                     sshAgent: "${pipelineParams.sshAgent}",
                                                     tarball: "${pipelineParams.app}",
                                                     gitTag: "${GIT_TAG}")
                            script {
                                if (pipelineParams.publishTestImage) {
                                    labelAndBuildDockerImage(name: "${pipelineParams.name}",
                                                             description: "${pipelineParams.description}",
                                                             dockerfile: "${pipelineParams.dockerfile}",
                                                             dockerBuildContextDir: "${pipelineParams.dockerBuildContextDir}",
                                                             imageReference: "${TEST_IMAGE_VERSIONED}",
                                                             dockerBuildTarget: 'testing',
                                                             masterBranch: "${pipelineParams.masterBranch}",
                                                             sshAgent: "${pipelineParams.sshAgent}",
                                                             tarball: "${pipelineParams.app}-test",
                                                             gitTag: "${GIT_TAG}")
                                }
                            }
                        }
                    }
                    // Package helm chart(s) if the chartsPath exists and contains 1 or more charts
                    stage('Charts') {
                        when { expression { return pipelineParams.includeCharts } }
                        steps {
                            packageHelmCharts(chartsPath: "${WORKSPACE}/kubernetes",
                                              appVersion: "${VERSION}",
                                              buildResultsPath: "${WORKSPACE}/build/results",
                                              buildDate: "${env.BUILD_DATE}")
                        }
                    }
                }
            }
            // TODO Again this wants encapsulating in a reusable function
            // Generate '.tar.gz' file of the image
            stage('Generate Docker Image Tarball') {
                steps {
                    dockerRetagAndSave(imageReference: "${IMAGE_VERSIONED}",
                                       imageRepo: 'sms.local:5000',
                                       imageName: "${IMAGE_NAME}",
                                       imageTag: "${IMAGE_TAG}",
                                       repository: "${pipelineParams.repository}")
                    script {
                        if (pipelineParams.publishTestImage) {
                            dockerRetagAndSave(imageReference: "${TEST_IMAGE_VERSIONED}",
                                               imageRepo: 'sms.local:5000',
                                               imageName: "${IMAGE_NAME}-test",
                                               imageTag: "${IMAGE_TAG}",
                                               repository: "${pipelineParams.repository}")
                        }
                    }
                }
            }
            stage('PR - Integration Testing') {
                when { changeRequest() }
                steps {
                    echo 'Log Stash: dockerBuildPipeline - PR - Integration Testing'
                    sh "if [ -f ${pipelineParams.integrationScript} ]; then ./${pipelineParams.integrationScript}; fi"
                }
            }
            // Publish
            stage('Publish') {
                parallel {
                    stage('Image') {
                        steps {
                            echo 'Log Stash: dockerBuildPipeline - Publish'
                            script {
                                env.CHART_VERSION_ENV = sh(script: """if [ -f .chart_version_file ]; then cat .chart_version_file; else echo \"\"; fi""", returnStdout: true).trim()
                            }
                            publishDockerImage(image: env.IMAGE_VERSIONED,
                                               imageTag: env.IMAGE_TAG,
                                               repository: "${pipelineParams.repository}",
                                               imageName: "${env.IMAGE_NAME}",
                                               masterBranch: "${pipelineParams.masterBranch}")
                            script {
                                if (pipelineParams.createSDPManifest) {
                                    dockerSDPManifestV1(workspace: env.workspace,
                                                        name: pipelineParams.name,
                                                        version: env.VERSION,
                                                        release: env.IMAGE_VERSIONED.split(':').last(),
                                                        disruptiveLevel: pipelineParams.disruptiveLevel,
                                                        description: pipelineParams.description,
                                                        repository: pipelineParams.repository,
                                                        imageName: "${IMAGE_NAME}",
                                                        ignoreImageVersion: pipelineParams.ignoreDockerVersionInManifest,
                                                        gitSHA: "${env.GIT_COMMIT}",
                                                        gitCloneURL: "${env.GIT_URL}",
                                                        gitBranch: "${env.GIT_BRANCH}",
                                                        gitRepository: "${env.GIT_REPO_NAME}",
                                                        product: pipelineParams.product,
                                                        masterBranch: "${env.MASTER_BRANCH}")
                                }
                            }
                            script {
                                if (pipelineParams.publishTestImage) {
                                    publishDockerImage(image: env.TEST_IMAGE_VERSIONED,
                                                       imageTag: env.IMAGE_TAG,
                                                       repository: "${pipelineParams.repository}",
                                                       imageName: "${env.IMAGE_NAME}-test",
                                                       masterBranch: "${pipelineParams.masterBranch}")
                                }
                            }
                        }
                    }
                    // Publish any helm charts that have been packaged on this run, otherwise silently moves on
                    stage ('Charts') {
                        when {
                            allOf {
                                expression {
                                    return pipelineParams.includeCharts == true
                                }
                            }
                        }
                        steps {
                            publishHelmCharts(chartsPath: "${WORKSPACE}/kubernetes",
                                              masterBranch: "${pipelineParams.masterBranch}")
                            script {
                                if (fileExists("${pipelineParams.helmSDPManifestConfig}")) {
                                    helmSDPManifestV1(workspace: env.workspace,
                                                      version: "${VERSION}",
                                                      chartsPath: "${WORKSPACE}/kubernetes",
                                                      configFile: pipelineParams.helmSDPManifestConfig,
                                                      buildDate: "${env.BUILD_DATE}",
                                                      gitTag: "${env.GIT_TAG}",
                                                      gitSHA: "${env.GIT_COMMIT}",
                                                      gitCloneURL: "${env.GIT_URL}",
                                                      gitBranch: "${env.GIT_BRANCH}",
                                                      gitRepository: "${env.GIT_REPO_NAME}",
                                                      masterBranch: "${pipelineParams.masterBranch}")
                                }
                            }
                        }
                    }
                }
            }
            stage('Post Build Script') {
                when {
                    expression {
                        fileExists("${pipelineParams.postBuildScript }") == true 
                    }
                }
                steps {
                    echo 'Log Stash: dockerBuildPipeline - Post Build Script'
                    sh "./${pipelineParams.postBuildScript}"
                }
            }
            // Once the image has been pushed, lets untag it so it's not sitting around and consuming
            // valuable hardware space.
            stage('Docker cleanup') {
                steps {
                    // docker rmi will remove the tagged tag without removing the original image
                    cleanupDockerImages(image: env.IMAGE_VERSIONED,
                                        imageTag: env.IMAGE_TAG,
                                        imageLatest: env.IMAGE_LATEST,
                                        imageName: env.IMAGE_NAME,
                                        repository: "${pipelineParams.repository}",
                                        masterBranch: "${pipelineParams.masterBranch}")
                }
            }
            stage('Trigger Events') {
                steps {
                    publishEvents(pipelineParams.sendEvents,
                                  pipelineParams.allBranches,
                                  pipelineParams.masterBranch)
                }
            }
        }
        post('Post-build steps') {
            always {
                script {
                    currentBuild.result = currentBuild.result == null ? 'SUCCESS' : currentBuild.result
                    env.IMAGE_TAG = env.IMAGE_TAG != null? env.IMAGE_TAG : "null"
                }
            }
            fixed {
                notifyBuildResult(headline: 'FIXED')
                script {
                    if ((pipelineParams.slackNotification[5] != false && skipSlack != true)) {
                        // Manually set the status to "FIXED" because it's not one of the accepted inputs for currentBuild.result
                        if (env.CHART_VERSION_ENV != null && "${CHART_VERSION_ENV}" != '') {
                            slackNotify(channel: "${pipelineParams.slackNotification[0]}",
                                        credential: "${pipelineParams.slackNotification[1]}",
                                        color: 'good',
                                        message: "Finished: ${env.JOB_NAME} | Build URL: ${env.BUILD_URL} | Status: FIXED\n IMAGE TAG: ${IMAGE_TAG}\nCHART VERSION: ${CHART_VERSION_ENV}")
                        } else {
                            slackNotify(channel: "${pipelineParams.slackNotification[0]}",
                                        credential: "${pipelineParams.slackNotification[1]}",
                                        color: 'good',
                                        message: "Finished: ${env.JOB_NAME} | Build URL: ${env.BUILD_URL} | Status: FIXED\n IMAGE TAG: ${IMAGE_TAG}")
                        }
                    }
                    // Set to true so the 'success' post section is skipped when the build result is 'fixed'
                    // Otherwise both 'fixed' and 'success' sections will execute due to Jenkins behavior
                    skipSuccess = true
                    findAndTransferArtifacts()
                }
            }
            failure {
                notifyBuildResult(headline: 'FAILED')
                script {
                    if ((pipelineParams.slackNotification[4] != false && skipSlack != true)) {
                        if (env.CHART_VERSION_ENV != null && "${CHART_VERSION_ENV}" != '') {
                            slackNotify(channel: "${pipelineParams.slackNotification[0]}",
                                        credential: "${pipelineParams.slackNotification[1]}",
                                        color: 'danger',
                                        message: "Finished: ${env.JOB_NAME} | Build URL: ${env.BUILD_URL} | Status: ${currentBuild.result}\n IMAGE TAG: ${IMAGE_TAG}\nCHART VERSION: ${CHART_VERSION_ENV}")
                        } else {
                            slackNotify(channel: "${pipelineParams.slackNotification[0]}",
                                        credential: "${pipelineParams.slackNotification[1]}",
                                        color: 'danger',
                                        message: "Finished: ${env.JOB_NAME} | Build URL: ${env.BUILD_URL} | Status: ${currentBuild.result}\n IMAGE TAG: ${IMAGE_TAG}")
                        }
                    }
                    if (pipelineParams.autoJira == true) {
                        if ("${env.GIT_BRANCH}" ==~ /^release\/.*/ || 
                            "${env.GIT_BRANCH}" ==~ "${pipelineParams.masterBranch}" ||
                            "${env.GIT_BRANCH}" ==~ 'auto-jira-validation') {
                            echo "Branch \"${env.GIT_BRANCH}\" is release or" +
                                ' master and no opt-out (autoJira = ' +
                                "${pipelineParams.autoJira}). Invoking the " +
                                'automatic Jira creation/update code.'
                            helper.autoJiraClone("${env.WORKSPACE}", "${pipelineParams.jiraProjectOverride}")
                        } else {
                            echo "Branch ${env.GIT_BRANCH} is not release or" +
                                'master and no opt-out (autoJira = ' +
                                "${pipelineParams.autoJira}). Skipping the " +
                                'automatic Jira creation/update code.'
                        }
                    } else {
                            echo 'Build has opted-out with autoJira = ' +
                                "${pipelineParams.autoJira}. Skipping the " +
                                'automatic Jira creation/update code.'
                    }
                }
            }
            success {
                script {
                    if ((pipelineParams.slackNotification[3] != false &&
                        skipSlack != true && 
                        skipSuccess != true))
                    {
                        // Have to manually set the currentBuild.result var to 'SUCCESS' when the job is successful, otherwise it appears as 'null'
                        currentBuild.result = 'SUCCESS'
                        if (env.CHART_VERSION_ENV != null && "${CHART_VERSION_ENV}" != '') {
                            slackNotify(channel: "${pipelineParams.slackNotification[0]}",
                                        credential: "${pipelineParams.slackNotification[1]}",
                                        color: 'good',
                                        message: "Finished: ${env.JOB_NAME} | Build URL: ${env.BUILD_URL} | Status: ${currentBuild.result}\n IMAGE TAG: ${IMAGE_TAG}\nCHART VERSION: ${CHART_VERSION_ENV}")
                        } else {
                            slackNotify(channel: "${pipelineParams.slackNotification[0]}",
                                        credential: "${pipelineParams.slackNotification[1]}",
                                        color: 'good',
                                        message: "Finished: ${env.JOB_NAME} | Build URL: ${env.BUILD_URL} | Status: ${currentBuild.result}\n IMAGE TAG: ${IMAGE_TAG}")
                        }
                    }
                    findAndTransferArtifacts()
                }
            }
        }
    }
}

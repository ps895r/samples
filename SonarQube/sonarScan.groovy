def call(pipelineParams) {
    // shutoff option
    if (pipelineParams.enableSonar == true) {
        // test coverage and other build results may be packaged for quality gating
        if (pipelineParams.sonarResults == true) {
            echo "build results found. Unstashing"
            dir('results') {
                unstash "buildResults"
            }
        }
        // check to see if there is script to add customized functionality
        if (fileExists("${pipelineParams.sonarPrepScript}") == true) {
            echo "sonar prep script found. Executing"
            sh """
                chmod u+x ./runSonarPrep.sh
                source ./${pipelineParams.sonarPrepScript}
            """
        }
        // api call to setup GitHub decorations. If already setup, no action is taken during secondary calls
        sh """
            curl -u "${SONAR_TOKEN}": -X POST \
                "https://sonarqube.hpc.amslabs.hpecorp.net/api/alm_settings/set_github_binding?almSetting=Github%20Test&monorepo=false&project="${GIT_REPO_NAME}"&repository=hpe/"${GIT_REPO_NAME}"&summaryCommentEnabled=true" \
                || echo "Not an hpe repo...skip GitHub Decoration setup"
        """
        // setup sonar-scanner from arti
        sh """
            wget -P ${WORKSPACE} https://arti.hpc.amslabs.hpecorp.net/artifactory/internal-misc-stable-local/dst/sonar-scanner-4.7.0.2747.tar
            mkdir ${WORKSPACE}/sonar
            tar -xvf ${WORKSPACE}/sonar-scanner-4.7.0.2747.tar -C ${WORKSPACE}/sonar
        """
        // scan with quality gate check
        if (pipelineParams.skipSonarQualityGate == false) {
        // Check if user wants to scan Java files
            if (pipelineParams.sonarScanJava == false) {
                echo "skip scanning Java files"
                sh "${WORKSPACE}/sonar/sonar-scanner-4.7.0.2747/bin/sonar-scanner " +
                        "-Dsonar.login=${SONAR_TOKEN} " +
                        "-Dsonar.host.url=https://sonarqube.hpc.amslabs.hpecorp.net " +
                        "-Dsonar.objc.file.suffixes=- " +
                        "-Dsonar.cpp.file.suffixes=- " +
                        "-Dsonar.c.file.suffixes=- " +
                        "-Dsonar.exclusions=**/*.java " +
                        "-Dsonar.qualitygate.wait " +
                        "-Dsonar.webhooks.project=${JENKINS_URL}/sonarqube-webhook/ " +
                        "-Dsonar.projectKey=${GIT_REPO_NAME} "
            } else {
                echo "scan Java Files"
                    sh "${WORKSPACE}/sonar/sonar-scanner-4.7.0.2747/bin/sonar-scanner " +
                        "-Dsonar.login=${SONAR_TOKEN} " +
                        "-Dsonar.host.url=https://sonarqube.hpc.amslabs.hpecorp.net " +
                        "-Dsonar.objc.file.suffixes=- " +
                        "-Dsonar.cpp.file.suffixes=- " +
                        "-Dsonar.c.file.suffixes=- " +
                        "-Dsonar.qualitygate.wait " +
                        "-Dsonar.webhooks.project=${JENKINS_URL}/sonarqube-webhook/ " +
                        "-Dsonar.projectKey=${GIT_REPO_NAME} "
            }
        } else {
            if (pipelineParams.sonarScanJava == false) {
                echo "skip scanning Java files"
                    sh "${WORKSPACE}/sonar/sonar-scanner-4.7.0.2747/bin/sonar-scanner " +
                        "-Dsonar.login=${SONAR_TOKEN} " +
                        "-Dsonar.host.url=https://sonarqube.hpc.amslabs.hpecorp.net " +
                        "-Dsonar.objc.file.suffixes=- " +
                        "-Dsonar.cpp.file.suffixes=- " +
                        "-Dsonar.c.file.suffixes=- " +
                        "-Dsonar.exclusions=**/*.java " +
                        "-Dsonar.webhooks.project=${JENKINS_URL}/sonarqube-webhook/ " +
                        "-Dsonar.projectKey=${GIT_REPO_NAME} "
            } else {
                 echo "scan Java Files"
                     sh "${WORKSPACE}/sonar/sonar-scanner-4.7.0.2747/bin/sonar-scanner " +
                          "-Dsonar.login=${SONAR_TOKEN} " +
                          "-Dsonar.host.url=https://sonarqube.hpc.amslabs.hpecorp.net " +
                          "-Dsonar.objc.file.suffixes=- " +
                          "-Dsonar.cpp.file.suffixes=- " +
                          "-Dsonar.c.file.suffixes=- " +
                          "-Dsonar.webhooks.project=${JENKINS_URL}/sonarqube-webhook/ " +
                          "-Dsonar.projectKey=${GIT_REPO_NAME} "
            }
        }
    }
}

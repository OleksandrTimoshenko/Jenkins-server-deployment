def getAdminUsers(){
  return ['admin']
}

def printMessage(color, message) {
    def ANSI_RESET = '\u001B[0m'
    def ANSI_YELLOW = '\u001B[33m'
    def ANSI_RED = '\u001B[31m'

    switch (color) {
        case 'WARNING':
            echo "${ANSI_YELLOW}${message}${ANSI_RESET}"
            break
        case 'ERROR':
            echo "${ANSI_RED}${message}${ANSI_RESET}"
            break
        default:
            echo "${ANSI_RESET}${message}${ANSI_RESET}"
            break
    }
}

pipeline {
    options {
        ansiColor('xterm')
    }
    agent {
        node {
            label 'swarm'
        }
    }
    environment {
        BB_WORKSPACE = "ci-integration1"
        BB_REPO = "test-for-ci"
        GIT_REPO = "git@bitbucket.org:ci-integration1/test-for-ci.git"
        BEARER_AUTH = credentials('BITBUCKET_BEARER_AUTH')
        FORCE_MERGE_APPROVED = false
        TESTS_OK = false
    }

    stages {
        stage('Get info about PR') {
            steps {
                script {
                    PR_INFO = sh(returnStdout: true, script: """curl \
                        -X GET -L \
                        -H \"Accept: application/json\" \
                        -H \"Authorization: Bearer ${env.BEARER_AUTH}\" \
                        https://api.bitbucket.org/2.0/repositories/${env.BB_WORKSPACE}/${env.BB_REPO}/pullrequests/${params.PR_ID}""").trim()
                }
            }
        }
        stage('Sheck that PR is open') {
            steps {
                script {
                    if (PR_INFO.contains('"state": "OPEN"')) {
                        printMessage("", "PR is open and can be merged...")
                    }
                    else {
                            printMessage('ERROR', "ERROR: PR with ID ${params.PR_ID} closed, doesn`t exist or you don`t have access to it!")
                            error("Issue with current PR")
                        }
                }
            }
        }
        stage('Check FORCE merge') {
            steps {
                script {
                    if (params.FORCE_MERGE == true) {
                        def BUILD_TRIGGER_BY = currentBuild.getBuildCauses()[0].userId
                        def WHO_CAN_MERGE_WITHOUT_TESTS = getAdminUsers()
                        if (BUILD_TRIGGER_BY in WHO_CAN_MERGE_WITHOUT_TESTS) {
                            FORCE_MERGE_APPROVED = true
                            printMessage ("", "Force merge activated, trigerred by ${BUILD_TRIGGER_BY} user.")
                            printMessage('WARNING', "WARNING: all tests will be skipped!!!")
                        }
                        else {
                            printMessage('ERROR', "ERROR: User ${BUILD_TRIGGER_BY} don`t have force merge permissions!!!")
                            error("Starting force merge for this user in not alloved!")
                        }
                    }
                }
            }
        }
        stage('Checkout Repository') {
            steps {
                script {
                    // Use the 'withCredentials' step to bind SSH credentials
                    withCredentials([sshUserPrivateKey(credentialsId: 'BB_SSH_KEY', keyFileVariable: 'SSH_PRIVATE_KEY')]) {
                        // Set up Git configuration with the SSH key
                        sh """
                            rm -rf ~/workspace/Merge_BB_PR/test-for-ci
                            rm -rf ~/.ssh
                            mkdir -p ~/.ssh
                            cp $SSH_PRIVATE_KEY ~/.ssh/id_rsa
                            chmod 600 ~/.ssh/id_rsa
                            echo 'Host *\n\tStrictHostKeyChecking no\n\n' >> ~/.ssh/config
                        """

                        // Checkout the Git repository
                        def prInfo = readJSON text: PR_INFO
                        def sourceBranch = prInfo.source.branch.name
                        sh "git clone -b ${sourceBranch} ${GIT_REPO}"
                    }
                }
            }
        }

        stage('Run tests') {
            steps {
                script {
                    def TESTS_RES = sh(script: 'python3 ~/workspace/Merge_BB_PR/test-for-ci/tests.py', returnStdout: true).trim()
                    echo "${TESTS_RES}"
                    if (TESTS_RES != "Tests passed!") {
                        printMessage('ERROR', "Tests failed")
                        error("Tests failed")
                    }
                    else {
                        TESTS_OK = true
                    }
                }
            }
        }
        // TODO: add approve check
        stage('Run merge') {
            when {
                anyOf {
                    expression {
                        return FORCE_MERGE_APPROVED == true
                    }
                    expression {
                        return TESTS_OK == true
                    }
                }
            }
            steps {
                script {
                    // TODO: add scrit for merge
                    printMessage("", "Run merge")
                }
            }
        }
    }
}
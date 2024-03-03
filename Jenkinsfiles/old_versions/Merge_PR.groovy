// Bitbucket REST API info
// https://developer.atlassian.com/cloud/bitbucket/rest/api-group-pullrequests/#api-repositories-workspace-repo-slug-pullrequests-pull-request-id-merge-post

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
            label 'python'
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
        stage('Get information about the pull request') {
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
        stage('Verify that the pull request is open') {
            steps {
                script {
                    if (PR_INFO.contains('"state": "OPEN"')) {
                        printMessage("", "The pull request is open and can be merged...")
                    }
                    else {
                            printMessage('ERROR', "ERROR: The pull request with ID ${params.PR_ID} is closed, does not exist, or you don't have access to it!")
                            error("There is an issue with the current pull request...")
                        }
                }
            }
        }
        stage('Verify the option for a FORCE merge') {
            steps {
                script {
                    if (params.FORCE_MERGE == true) {
                        def BUILD_TRIGGER_BY = currentBuild.getBuildCauses()[0].userId
                        def WHO_CAN_MERGE_WITHOUT_TESTS = getAdminUsers()
                        if (BUILD_TRIGGER_BY in WHO_CAN_MERGE_WITHOUT_TESTS) {
                            FORCE_MERGE_APPROVED = true
                            printMessage ("", "Force merge activated, triggered by user ${BUILD_TRIGGER_BY}.")
                            printMessage('WARNING', "WARNING: Force merge!")
                        }
                        else {
                            printMessage('ERROR', "ERROR: User ${BUILD_TRIGGER_BY} does not have force merge permissions!!!")
                            error("Starting a force merge for this user is not allowed.")
                        }
                    }
                }
            }
        }
        stage('Check out the repository') {
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
                        printMessage('ERROR', "Tests failed!")
                        error("Tests failed!")
                    }
                    else {
                        TESTS_OK = true
                    }
                }
            }
        }
        stage("Tests have been approved") {
            when {
                expression {
                    return FORCE_MERGE_APPROVED != true
                }
            }
            steps {
                script {
                    def NOT_APPROVED_USERS = []
                    def prInfo = readJSON text: PR_INFO
                    def APPROVERS = prInfo.reviewers.display_name
                    def PARTISIPANTS = prInfo.participants
                    if (APPROVERS == []) {
                        printMessage('ERROR', "ERROR: It seems like this pull request doesn't have any approver...")
                        error("It seems like this pull request doesn't have any approver...")
                    }
                    else {
                        printMessage('', "Found reviewer(s) for this pull request: ${APPROVERS}")
                    }
                    PARTISIPANTS.each { participant ->
                        if (participant.role == 'REVIEWER' && participant.approved == false) {
                           NOT_APPROVED_USERS <<  participant.user.display_name
                        }
                    }
                    if (NOT_APPROVED_USERS.size() != 0) {
                        printMessage('ERROR', "ERROR: The approver(s) haven't approved the pull request yet: ${NOT_APPROVED_USERS}")
                        error("Doesn't have approval from all approvers.")
                    }
                }
            }
        }
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
                    def BUILD_TRIGGER_BY = currentBuild.getBuildCauses()[0].userId
                    sh(script: """curl \
                        --request POST \
                        --url 'https://api.bitbucket.org/2.0/repositories/${env.BB_WORKSPACE}/${env.BB_REPO}/pullrequests/${params.PR_ID}/merge' \
                        --header 'Authorization: Bearer ${env.BEARER_AUTH}' \
                        --header 'Accept: application/json' \
                        --header 'Content-Type: application/json' \
                        --data '{
                            "type": "string",
                            "message": "PR was merged via Jenkins pipeline by user ${BUILD_TRIGGER_BY}",
                            "close_source_branch": ${params.CLOSE_SOURCE_BRANCH},
                            "merge_strategy": "${params.MERGE_STRATEGY}"
                        }'""")
                }
            }
        }
    }
}
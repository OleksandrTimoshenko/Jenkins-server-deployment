// Bitbucket REST API info
// https://developer.atlassian.com/cloud/bitbucket/rest/api-group-pullrequests/#api-repositories-workspace-repo-slug-pullrequests-pull-request-id-merge-post
@Library('Default_jenkins_lib@master') import main.Utilities
lib = new Utilities(this)

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
        prInfo =null
    }

    stages {
        stage('Get information about the pull request') {
            steps {
                script {
                    prInfo = lib.getPRInfo()
                }
            }
        }
        stage('Verify that the pull request is open') {
            steps {
                script {
                    lib.getPRState(prInfo)
                }
            }
        }
        stage('Verify the option for a FORCE merge') {
            steps {
                script {
                    FORCE_MERGE_APPROVED = lib.checkForceMerge()
                }
            }
        }
        stage('Check out the repository') {
            steps {
                script {
                    withCredentials([sshUserPrivateKey(credentialsId: 'BB_SSH_KEY', keyFileVariable: 'SSH_PRIVATE_KEY')]) {
                        lib.checkout(prInfo, SSH_PRIVATE_KEY)
                    }
                }
            }
        }

        stage('Run tests') {
            steps {
                script {
                    TESTS_OK = lib.runTests()
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
                    lib.chechApproves(prInfo)
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
                    lib.merge()
                }
            }
        }
    }
}
/*
    To learn how to use this sample pipeline, follow the guide below and enter the
    corresponding values for your environment and for this repository:
    - https://github.com/ibm-cloud-architecture/refarch-cloudnative-devops-kubernetes
*/

// Environment
def clusterURL = env.CLUSTER_URL
def clusterAccountId = env.CLUSTER_ACCOUNT_ID
def clusterCredentialId = env.CLUSTER_CREDENTIAL_ID ?: "cluster-credentials"

// Pod Template
def podLabel = "inventory"
def cloud = env.CLOUD ?: "kubernetes"
def registryCredsID = env.REGISTRY_CREDENTIALS ?: "registry-credentials-id"
def serviceAccount = env.SERVICE_ACCOUNT ?: "jenkins"

// Pod Environment Variables
def namespace = env.NAMESPACE ?: "default"
def registry = env.REGISTRY ?: "docker.io"
def imageName = env.IMAGE_NAME ?: "ibmcase/bluecompute-inventory"
def serviceLabels = env.SERVICE_LABELS ?: "app=inventory,tier=backend" //,version=v1"
def microServiceName = env.MICROSERVICE_NAME ?: "inventory"
def servicePort = env.MICROSERVICE_PORT ?: "8080"

// External Test Database Parameters
// For username and passwords, set MYSQL_USER (as string parameter) and MYSQL_PASSWORD (as password parameter)
//     - These variables get picked up by the Java application automatically
//     - There were issues with Jenkins credentials plugin interfering with setting up the password directly

def mySQLHost = env.MYSQL_HOST
def mySQLPort = env.MYSQL_PORT ?: "3306"
def mySQLDatabase = env.MYSQL_DATABASE ?: "inventorydb"
def mySQLCredsId = env.MYSQL_CREDENTIALS ?: "inventory-mysql-id"

/*
  Optional Pod Environment Variables
 */
def helmHome = env.HELM_HOME ?: env.JENKINS_HOME + "/.helm"

podTemplate(label: podLabel, cloud: cloud, serviceAccount: serviceAccount, namespace: namespace, envVars: [
        envVar(key: 'CLUSTER_URL', value: clusterURL),
        envVar(key: 'CLUSTER_ACCOUNT_ID', value: clusterAccountId),
        envVar(key: 'NAMESPACE', value: namespace),
        envVar(key: 'REGISTRY', value: registry),
        envVar(key: 'IMAGE_NAME', value: imageName),
        envVar(key: 'SERVICE_LABELS', value: serviceLabels),
        envVar(key: 'MICROSERVICE_NAME', value: microServiceName),
        envVar(key: 'MICROSERVICE_PORT', value: servicePort),
        envVar(key: 'MYSQL_HOST', value: mySQLHost),
        envVar(key: 'MYSQL_PORT', value: mySQLPort),
        envVar(key: 'MYSQL_DATABASE', value: mySQLDatabase),
        envVar(key: 'HELM_HOME', value: helmHome)
    ],
    volumes: [
        hostPathVolume(mountPath: '/home/gradle/.gradle', hostPath: '/tmp/jenkins/.gradle'),
        hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock')
    ],
    containers: [
        containerTemplate(name: 'jdk', image: 'ibmcase/openjdk-bash:latest', ttyEnabled: true, command: 'cat'),
        containerTemplate(name: 'docker' , image: 'ibmcase/docker-bash:latest', ttyEnabled: true, command: 'cat'),
        containerTemplate(name: 'kubernetes', image: 'ibmcase/jenkins-slave-utils:latest', ttyEnabled: true, command: 'cat')
  ]) {

    node(podLabel) {
        checkout scm

        // Local
        container(name:'jdk', shell:'/bin/bash') {
            stage('Local - Build and Unit Test') {
                sh """
                #!/bin/bash

                ./gradlew build
                """
            }
            stage('Local - Run and Test') {
                sh """
                #!/bin/bash

                JAVA_OPTS="-Dspring.datasource.url=jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DATABASE}"
                JAVA_OPTS="\${JAVA_OPTS} -Dspring.datasource.port=${MYSQL_PORT}"
                JAVA_OPTS="\${JAVA_OPTS} -Dserver.port=${MICROSERVICE_PORT}"

                java \${JAVA_OPTS} -jar build/libs/micro-inventory-0.0.1.jar &

                PID=`echo \$!`

                # Let the application start
                sleep 25

                # Run tests
                set +x
                bash scripts/api_tests.sh 127.0.0.1 ${MICROSERVICE_PORT}
                set -x;

                # Kill process
                kill \${PID}
                """
            }
        }

        // Docker
        container(name:'docker', shell:'/bin/bash') {
            stage('Docker - Build Image') {
                sh """
                #!/bin/bash

                # Get image
                if [ "${REGISTRY}" = "docker.io" ]; then
                    IMAGE=${IMAGE_NAME}:${env.BUILD_NUMBER}
                else
                    IMAGE=${REGISTRY}/${NAMESPACE}/${IMAGE_NAME}:${env.BUILD_NUMBER}
                fi

                docker build -t \${IMAGE} .
                """
            }
            stage('Docker - Run and Test') {
                sh """
                #!/bin/bash

                # Get image
                if [ "${REGISTRY}" = "docker.io" ]; then
                    IMAGE=${IMAGE_NAME}:${env.BUILD_NUMBER}
                else
                    IMAGE=${REGISTRY}/${NAMESPACE}/${IMAGE_NAME}:${env.BUILD_NUMBER}
                fi

                # Kill Container if it already exists
                docker kill ${MICROSERVICE_NAME} || true
                docker rm ${MICROSERVICE_NAME} || true

                # Start Container
                echo "Starting ${MICROSERVICE_NAME} container"
                set +x
                docker run --name ${MICROSERVICE_NAME} -d \
                    -p ${MICROSERVICE_PORT}:${MICROSERVICE_PORT} \
                    -e SERVICE_PORT=${MICROSERVICE_PORT} \
                    -e MYSQL_HOST=${MYSQL_HOST} \
                    -e MYSQL_PORT=${MYSQL_PORT} \
                    -e MYSQL_USER=${MYSQL_USER} \
                    -e MYSQL_PASSWORD=${MYSQL_PASSWORD} \
                    -e MYSQL_DATABASE=${MYSQL_DATABASE} \${IMAGE}
                set -x

                # Let the application start
                sleep 25

                # Check that application started successfully
                docker ps

                # Check the logs
                docker logs ${MICROSERVICE_NAME}

                # Get the container IP Address
                CONTAINER_IP=`docker inspect ${MICROSERVICE_NAME} | jq -r '.[0].NetworkSettings.IPAddress'`

                # Run tests
                bash scripts/api_tests.sh \${CONTAINER_IP} ${MICROSERVICE_PORT}

                # Kill Container
                docker kill ${MICROSERVICE_NAME} || true
                docker rm ${MICROSERVICE_NAME} || true
                """
            }
            stage('Docker - Push Image to Registry') {
                withCredentials([usernamePassword(credentialsId: registryCredsID,
                                               usernameVariable: 'USERNAME',
                                               passwordVariable: 'PASSWORD')]) {
                    sh """
                    #!/bin/bash

                    # Get image
                    if [ "${REGISTRY}" = "docker.io" ]; then
                        IMAGE=${IMAGE_NAME}:${env.BUILD_NUMBER}
                    else
                        IMAGE=${REGISTRY}/${NAMESPACE}/${IMAGE_NAME}:${env.BUILD_NUMBER}
                    fi

                    docker login -u ${USERNAME} -p ${PASSWORD} ${REGISTRY}

                    docker push \${IMAGE}
                    """
                }
            }
        }

        // Kubernetes
        container(name:'kubernetes', shell:'/bin/bash') {
            stage('Initialize CLIs') {
                withCredentials([usernamePassword(credentialsId: clusterCredentialId,
                                               passwordVariable: 'CLUSTER_PASSWORD',
                                               usernameVariable: 'CLUSTER_USERNAME')]) {
                    sh """
                    echo "Initializing Helm ..."
                    export HELM_HOME=${HELM_HOME}
                    helm init -c

                    echo "Login with cloudctl ..."
                    cloudctl login -a ${CLUSTER_URL} -u ${CLUSTER_USERNAME}  -p "${CLUSTER_PASSWORD}" -c ${CLUSTER_ACCOUNT_ID} -n ${NAMESPACE} --skip-ssl-validation
                    """
                }
            }
            stage('Kubernetes - Deploy new Docker Image') {
                sh """
                #!/bin/bash

                # Get image
                if [ "${REGISTRY}" = "docker.io" ]; then
                    IMAGE=${IMAGE_NAME}
                else
                    IMAGE=${REGISTRY}/${NAMESPACE}/${IMAGE_NAME}
                fi

                # Build PARAMETERS
                NAME="${MICROSERVICE_NAME}-v${env.BUILD_NUMBER}"
                echo "Installing chart/${MICROSERVICE_NAME} chart with name "\${NAME}" and waiting for pods to be ready"

                set +x
                helm upgrade --install \${NAME} \
                    --set fullnameOverride=\${NAME} \
                    --set image.repository=\${IMAGE} \
                    --set image.tag=${env.BUILD_NUMBER} \
                    --set labels.version=v${env.BUILD_NUMBER} \
                    --set service.externalPort=${MICROSERVICE_PORT} \
                    --set mysql.host=${MYSQL_HOST} \
                    --set mysql.port=${MYSQL_PORT} \
                    --set mysql.database=${MYSQL_DATABASE} \
                    --set mysql.user=${MYSQL_USER} \
                    --set mysql.password=${MYSQL_PASSWORD} \
                    chart/${MICROSERVICE_NAME} --wait --tls
                set -x

                READY=`kubectl get deployments \${NAME} -o yaml | grep "readyReplicas" | awk '{print $2}'`
                echo \${READY}

                until [ -n "\${READY}" ] && [ \${READY} -ge 1 ]; do
                    READY=`kubectl get deployments \${NAME} -o yaml | grep "readyReplicas" | awk '{print $2}'`
                    kubectl get deployments -o wide;
                    echo "Waiting for \${NAME} to be ready";
                    sleep 10;
                done
                """
            }
            stage('Kubernetes - Test') {
                sh """
                #!/bin/bash

                # Get deployment
                QUERY_LABELS="${SERVICE_LABELS},version=v${env.BUILD_NUMBER}"
                POD=`kubectl --namespace=${NAMESPACE} get pods -l \${QUERY_LABELS} -o name | head -n 1`

                # Wait for deployment to start accepting connections
                sleep 35

                # Port forwarding & logs
                kubectl port-forward \${POD} ${MICROSERVICE_PORT}:${MICROSERVICE_PORT} &
                kubectl logs -f \${POD} &
                echo "Sleeping for 3 seconds while connection is established..."
                sleep 3

                # Run tests
                bash scripts/api_tests.sh 127.0.0.1 ${MICROSERVICE_PORT}

                # Kill port forwarding
                killall kubectl || true
                """
            }
        }
    }
}
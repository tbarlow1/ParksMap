#!groovy

// Run this pipeline on the custom Maven Slave ('maven-appdev')
// Maven Slaves have JDK and Maven already installed
// 'maven-appdev' has skopeo installed as well.
node('maven-appdev') {
  // Define Maven Command. Make sure it points to the correct
  // settings for our Nexus installation (use the service to
  // bypass the router). The file nexus_openshift_settings.xml
  // needs to be in the Source Code repository.
  def mvnCmd = "mvn -s ./nexus_settings_openshift.xml"

  // Checkout Source Code
  stage('Checkout Source') {
    git credentialsId: 'cd9f9f74-1c9f-422c-97f6-2b99365606ba', url: 'http://gogs-tjb-gogs.apps.na39.openshift.opentlc.com/Homework/parks-map.git'
    
  }

  // The following variables need to be defined at the top level
  // and not inside the scope of a stage - otherwise they would not
  // be accessible from other stages.
  // Extract version and other properties from the pom.xml
  def groupId    = getGroupIdFromPom("parksmap/pom.xml")
  def artifactId = getArtifactIdFromPom("parksmap/pom.xml")
  def version    = getVersionFromPom("parksmap/pom.xml")

  // Set the tag for the development image: version + build number
  def devTag  = "${version}-${BUILD_NUMBER}"
  // Set the tag for the production image: version
  def prodTag = "${version}"

  // Using Maven build the war file
  // Do not run tests in this step
  stage('Build war') {
    echo "Building version ${version}"
    sh "${mvnCmd} -f ./mlbparks/pom.xml clean package -DskipTests"
  }

  // Using Maven run the unit tests
  stage('Unit Tests') {
    echo "Running Unit Tests"
    sh "${mvnCmd} -f ./mlbparks/pom.xml clean test"
  }

  // Using Maven call SonarQube for Code Analysis
  stage('Code Analysis') {
    echo "Running Code Analysis"
    sh "${mvnCmd} -f ./mlbparks/pom.xml sonar:sonar -Dsonar.host.url=http://sonarqube-tjb-sonarqube.apps.na39.openshift.opentlc.com/ -Dsonar.projectName=${JOB_BASE_NAME}-mlbparks-${devTag}"

  }

  // Publish the built war file to Nexus
  stage('Publish to Nexus') {
    echo "Publish to Nexus"
    sh "${mvnCmd} deploy -f ./mlbparks/pom.xml -DskipTests=true -DaltDeploymentRepository=nexus::default::http://nexus3.tjb-nexus.svc.cluster.local:8081/repository/releases"
    
  }

  // Build the OpenShift Image in OpenShift and tag it.
  stage('Build and Tag OpenShift Image') {
    echo "Building OpenShift container image tasks:${devTag}"
    
    sh "oc start-build mlbparks --follow --from-file=./mlbparks/target/mlbparks.war -n tjb-development"
    openshiftTag alias: 'false', destStream: 'mlbparks', destTag: devTag, destinationNamespace: 'tjb-development', namespace: 'tjb-development', srcStream: 'mlbparks', srcTag: 'latest', verbose: 'false'
  }

  // Deploy the built image to the Development Environment.
  stage('Deploy to Dev') {
    echo "Deploying container image to Development Project"
    
    sh "oc set image dc/mlbparks mlbparks=docker-registry.default.svc:5000/tjb-development/mlbparks:${devTag} -n tjb-development"
    
    openshiftDeploy depCfg: 'mlbparks', namespace: 'tjb-development', verbose: 'false', waitTime: '', waitUnit: 'sec'
    openshiftVerifyDeployment depCfg: 'mlbparks', namespace: 'tjb-development', replicaCount: '1', verbose: 'false', verifyReplicaCount: 'false', waitTime: '', waitUnit: 'sec'
    openshiftVerifyService namespace: 'tjb-development', svcName: 'mlbparks', verbose: 'false'
  }

  def result = ""
  def success = false
  // Run Integration Tests in the Development Environment.
  stage('Integration Tests') {
    echo "Running Integration Tests"
    for (i = 0; i < 10; i++) {
        result = sh(returnStdout: true, script: "curl -G http://mlbparks-tjb-development.apps.na39.openshift.opentlc.com/ws/healthz/")
        if (result == "OK") {
            echo "Integration test passed successfully"
            success = true
            break
        }   else {
            echo "mlbparks failed to respond. Trying again"
            sleep(30)
        }
    }
    if (success == false) {
        echo "Integration test failed after 5 minutes"
    }
  }

  // Copy Image to Nexus Docker Registry
  stage('Copy Image to Nexus Docker Registry') {
    echo "Copy image to Nexus Docker Registry"
    
    
    sh "skopeo copy --src-tls-verify=false --dest-tls-verify=false --src-creds openshift:\$(oc whoami -t) --dest-creds admin:admin123 docker://docker-registry.default.svc.cluster.local:5000/tjb-development/mlbparks:${devTag} docker://nexus-registry.tjb-nexus.svc.cluster.local:5000/mlbparks:${devTag}"

  // Tag the built image with the production tag.
  // Replace xyz-tasks-dev with the name of your dev project
  openshiftTag alias: 'false', destStream: 'mlbparks', destTag: prodTag, destinationNamespace: 'tjb-development', namespace: 'tjb-development', srcStream: 'mlbparks', srcTag: devTag, verbose: 'false'
  }

  // Blue/Green Deployment into Production
  // -------------------------------------
  // Do not activate the new version yet.
  def destApp   = "mlbparks-green"
  def activeApp = ""

  stage('Blue/Green Production Deployment') {
	  activeApp = sh(returnStdout: true, script: "oc get service mlbparks-blue -n tjb-production -o jsonpath='{ .metadata.labels.type }'").trim()
	  if (activeApp == "parksmap-backend") {
		  destApp = "mlbparks-green"
		  activeApp = "mlbparks-blue"
	  } else {
		  destApp = "mlbparks-blue"
		  activeApp = "mlbparks-green"
	  }
	  echo "Active Application:      " + activeApp
      echo "Destination Application: " + destApp
	  
	  // Update the Image on the Production Deployment Config
      sh "oc set image dc/${destApp} ${destApp}=docker-registry.default.svc:5000/tjb-development/mlbparks:${prodTag} -n tjb-production"
  
      openshiftDeploy depCfg: destApp, namespace: 'tjb-production', verbose: 'false', waitTime: '', waitUnit: 'sec'
      openshiftVerifyDeployment depCfg: destApp, namespace: 'tjb-production', replicaCount: '1', verbose: 'false', verifyReplicaCount: 'true', waitTime: '', waitUnit: 'sec'
      openshiftVerifyService namespace: 'tjb-production', svcName: destApp, verbose: 'false'
  
  }

  stage('Switch over to new Version') {
    input "Switch Production?"
	  
    echo "Switching Production application to ${destApp}."
    
	sh "oc label service ${destApp} --overwrite=true -n tjb-production type=parks-backend"
	sh "oc label service ${activeApp} --overwrite=true -n tjb-production type-"
  }
}

// Convenience Functions to read variables from the pom.xml
// Do not change anything below this line.
// --------------------------------------------------------
def getVersionFromPom(pom) {
  def matcher = readFile(pom) =~ '<version>(.+)</version>'
  matcher ? matcher[0][1] : null
}
def getGroupIdFromPom(pom) {
  def matcher = readFile(pom) =~ '<groupId>(.+)</groupId>'
  matcher ? matcher[0][1] : null
}
def getArtifactIdFromPom(pom) {
  def matcher = readFile(pom) =~ '<artifactId>(.+)</artifactId>'
  matcher ? matcher[0][1] : null
}

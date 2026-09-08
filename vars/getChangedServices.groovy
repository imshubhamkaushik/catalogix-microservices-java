// jenkins/shared-library/vars/getChangedServices.groovy
//
// Returns the subset of `services` whose source actually changed since the
// previous commit. Used to skip the expensive test stage for services no
// one touched — NOT used to skip Docker build/scan/push (see Jenkinsfile
// header comment on why every image still gets built+pushed every run).
//
// Safety fallback: if the parent pom.xml, any Jenkinsfile, or the Helm
// chart changed, every service is treated as changed — a parent POM bump
// or CI logic change can affect all modules even if their own src/ didn't move.
//
// Usage:
//   def changed = getChangedServices(BACKEND_SERVICES, [prefix: 'backend/'])
//   def changedFrontend = getChangedServices(['frontend-svc'], [prefix: ''])

def call(List<String> services, Map opts = [:]) {
    String prefix = opts.prefix ?: ''

    def prevCommitExists = sh(
        script: "git rev-parse HEAD~1 > /dev/null 2>&1 && echo yes || echo no",
        returnStdout: true
    ).trim()

    if (prevCommitExists == 'no') {
        echo "getChangedServices: no previous commit (first build on this branch?) — treating all as changed."
        return services
    }

    def diffOutput = sh(
        script: "git diff --name-only HEAD~1 HEAD",
        returnStdout: true
    ).trim()

    def changedFiles = diffOutput ? diffOutput.split('\n') as List : []

    def sharedFilesTouched = changedFiles.any {
        it == 'pom.xml' || it.startsWith('Jenkinsfile') || it.startsWith('helm/')
    }

    if (sharedFilesTouched) {
        echo "getChangedServices: shared file (parent pom.xml / Jenkinsfile / helm/) changed — treating all as changed."
        return services
    }

    def changed = services.findAll { svc ->
        def svcPrefix = "${prefix}${svc}/"
        changedFiles.any { it.startsWith(svcPrefix) }
    }

    echo "getChangedServices: ${changed.isEmpty() ? '(none)' : changed.join(', ')}"
    return changed
}

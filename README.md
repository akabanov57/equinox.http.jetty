# equinox.http.jetty.ms
A thin layer that exposes one instance of an embedded Jetty server as a compliant OSGi HTTP service. The service is easy to configure and can be used with [https://felix.apache.org/documentation/subprojects/apache-felix-file-install.html](https://felix.apache.org/documentation/subprojects/apache-felix-file-install.html). This can be useful when debugging.

Original project [https://www.eclipse.org/equinox/server/](https://www.eclipse.org/equinox/server/)
# How to
It is assumed that you know how to use [bndtools](https://bndtools.org/).
1. Download Equinox SDK [https://download.eclipse.org/equinox/](https://download.eclipse.org/equinox/)
2. Unzip it somewhere in your home directory.
3. export EQUINOX_P2="path to Equinox SDK directory"
4. Install bndtools.
5. Import this project from git.
6. Go to org.foo.hello.world directory.
7. Open hello.bndrun.
8. Resolve, Run OSGi.
9. In browser https://localhost:8443/hello

	
@echo off
rem IPED processing launcher (Java 21).
rem Runs iped.jar with the JRE bundled under jre/, avoiding any Java found on
rem the system. Interim replacement for iped.exe, which is a prebuilt launcher
rem that still selects a Java 11 from the Windows registry and ignores the
rem bundled jre/ and JAVA_HOME. Remove once iped.exe is rebuilt for Java 21.
setlocal
set "IPED_HOME=%~dp0"
rem -Djava.security.manager=allow is propagated to the forked JVM, where IPED
rem installs a SecurityManager (blocked by default on Java 18+).
"%IPED_HOME%jre\bin\java.exe" -Djava.security.manager=allow -jar "%IPED_HOME%iped.jar" %*

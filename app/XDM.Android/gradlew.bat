@ECHO OFF
SET DIR=%~dp0
SET CLASSPATH=%DIR%gradle\wrapper\gradle-wrapper.jar
IF NOT EXIST "%CLASSPATH%" (
  ECHO Missing gradle\wrapper\gradle-wrapper.jar.
  ECHO Run: gradle wrapper --gradle-version 9.4.1
  EXIT /B 1
)
java -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

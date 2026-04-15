$env:JAVA_HOME = "C:\Program Files\Common Files\Oracle\Java\javapath"
$env:PATH = "C:\Program Files\Common Files\Oracle\Java\javapath;$env:PATH"
$ErrorActionPreference = "Stop"
$output = & "D:\trace\gradlew.bat" "-p", "D:\trace", "compileDebugKotlin", "--no-daemon" 2>&1
$output | Select-Object -Last 100

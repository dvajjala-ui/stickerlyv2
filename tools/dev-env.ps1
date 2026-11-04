# Dot-source before building: . .\tools\dev-env.ps1
$env:JAVA_HOME = 'D:\dev\jdk\jdk-21.0.13+9'
$env:ANDROID_HOME = 'D:\dev\android-sdk'
$env:ANDROID_SDK_ROOT = 'D:\dev\android-sdk'
$env:GRADLE_USER_HOME = 'D:\dev\gradle-home'
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"

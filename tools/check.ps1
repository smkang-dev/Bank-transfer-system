param([ValidateSet("Unit","Mysql","Migrate")][string]$Mode="Unit")
$ErrorActionPreference="Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)
New-Item -ItemType Directory -Force target/manual-classes | Out-Null
$taskFiles=@(Get-ChildItem src,tests -Filter *.java -Recurse | ForEach-Object {
    '"' + $_.FullName.Replace('\','/') + '"'
})
[System.IO.File]::WriteAllLines(
    (Join-Path (Get-Location) "target/manual-sources.txt"),
    $taskFiles,
    (New-Object System.Text.UTF8Encoding($false))
)
& javac --release 21 -encoding UTF-8 -d target/manual-classes "@target/manual-sources.txt"
if($LASTEXITCODE -ne 0){throw "컴파일 실패"}
if($Mode -eq "Unit"){
    & java -cp target/manual-classes BankUnitTests
} else {
    & mvn dependency:copy-dependencies "-DincludeScope=runtime"
    if($LASTEXITCODE -ne 0){throw "JDBC 드라이버 다운로드 실패. 기존 pom.xml 의존성을 확인하세요."}
    $taskClasspath="target/manual-classes;target/dependency/*"
    if($Mode -eq "Mysql"){
        & java -cp $taskClasspath BankMysqlTests
    } else {
        & java -cp $taskClasspath main.MigratePasswords --confirm-backup
    }
}
if($LASTEXITCODE -ne 0){throw "검증/변환 실패"}

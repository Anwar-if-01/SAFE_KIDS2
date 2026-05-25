$file = "c:\Users\MURAD\AndroidStudioProjects\SAFE_KIDS2\app\src\main\java\com\example\safe_kids\ui\parent\ParentDashboardActivity.kt"
$content = Get-Content $file -Raw
$content = $content -replace 'import androidx\.compose\.material\.icons\.filled\.Warning', "import androidx.compose.material.icons.filled.Warning`nimport androidx.compose.material.icons.filled.*"
Set-Content $file -Value $content -Encoding UTF8
Write-Host "Wildcard import added."

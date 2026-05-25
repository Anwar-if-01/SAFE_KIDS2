$file = "c:\Users\MURAD\AndroidStudioProjects\SAFE_KIDS2\app\src\main\java\com\example\safe_kids\ui\parent\ParentDashboardActivity.kt"
$content = Get-Content $file -Raw

$content = $content -replace 'Icons\.Default\.LockClock', 'Icons.Default.Lock'
$content = $content -replace 'Icons\.Default\.Bedtime', 'Icons.Default.DateRange'
$content = $content -replace 'Icons\.Default\.Apps', 'Icons.Default.Menu'
$content = $content -replace 'Icons\.Default\.NotificationsActive', 'Icons.Default.Notifications'
$content = $content -replace 'Icons\.Default\.Visibility', 'Icons.Default.Info'
$content = $content -replace 'Icons\.Default\.Image', 'Icons.Default.AccountBox'
$content = $content -replace 'Icons\.Default\.Message', 'Icons.Default.Email'
$content = $content -replace 'Icons\.Default\.Language', 'Icons.Default.Home'
$content = $content -replace 'Icons\.Default\.History', 'Icons.Default.List'
$content = $content -replace 'Icons\.Default\.CheckCircle', 'Icons.Default.Done'
$content = $content -replace 'Icons\.Default\.Sos', 'Icons.Default.Warning'
$content = $content -replace 'Icons\.Default\.SystemUpdateAlt', 'Icons.Default.Build'
$content = $content -replace 'Icons\.Default\.Security', 'Icons.Default.Lock'
$content = $content -replace 'Icons\.Default\.QuestionMark', 'Icons.Default.Info'
$content = $content -replace 'Icons\.Default\.VerifiedUser', 'Icons.Default.Done'
$content = $content -replace 'Icons\.Default\.CameraAlt', 'Icons.Default.AccountBox'
$content = $content -replace 'Icons\.Default\.Screenshot', 'Icons.Default.Phone'
$content = $content -replace 'Icons\.Default\.HeadsetMic', 'Icons.Default.Call'
$content = $content -replace 'Icons\.Default\.ChevronRight', 'Icons.Default.KeyboardArrowRight'

Set-Content $file -Value $content -Encoding UTF8
Write-Host "Icons replaced."

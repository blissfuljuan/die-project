# Smoke test of a RUNNING DIE stack. Windows PowerShell 5.1 or PowerShell 7.
#   $env:DIE_BOOTSTRAP_ADMIN_EMAIL = "admin@example.edu"; $env:DIE_BOOTSTRAP_ADMIN_PASSWORD = "<password>"; .\scripts\smoke.ps1
# Exit 0 = passed, 1 = a check failed, 2 = missing credentials.
$ErrorActionPreference = 'Stop'
$front = if ($env:SMOKE_FRONTEND_URL) { $env:SMOKE_FRONTEND_URL } else { 'http://localhost:5173' }
$api   = if ($null -ne $env:SMOKE_API_URL) { $env:SMOKE_API_URL } else { 'http://localhost:8080' }
$email = if ($env:SMOKE_ADMIN_EMAIL) { $env:SMOKE_ADMIN_EMAIL } else { $env:DIE_BOOTSTRAP_ADMIN_EMAIL }
$pass  = if ($env:SMOKE_ADMIN_PASSWORD) { $env:SMOKE_ADMIN_PASSWORD } else { $env:DIE_BOOTSTRAP_ADMIN_PASSWORD }
$timeout = if ($env:SMOKE_JOB_TIMEOUT) { [int]$env:SMOKE_JOB_TIMEOUT } else { 90 }
if (-not $email -or -not $pass) { Write-Host 'Set DIE_BOOTSTRAP_ADMIN_EMAIL / DIE_BOOTSTRAP_ADMIN_PASSWORD'; exit 2 }

$rid = "smoke-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())-$(Get-Random -Maximum 999)"
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$script:failed = $false
function Ok($m)  { Write-Host "  PASS  $m" }
function Bad($m) { Write-Host "  FAIL  $m"; $script:failed = $true }
function Check($cond, $m) { if ($cond) { Ok $m } else { Bad $m } }

function Req([string]$method, [string]$url, $json = $null) {
  $h = @{ 'X-Request-Id' = $rid }
  $c = $session.Cookies.GetCookies([Uri]$front) | Where-Object { $_.Name -eq 'XSRF-TOKEN' } | Select-Object -Last 1
  if ($c) { $h['X-XSRF-TOKEN'] = $c.Value }
  $p = @{ Uri = $url; Method = $method; Headers = $h; WebSession = $session; UseBasicParsing = $true; TimeoutSec = 30 }
  if ($null -ne $json) { $p['Body'] = ($json | ConvertTo-Json -Compress); $p['ContentType'] = 'application/json' }
  try {
    $r = Invoke-WebRequest @p
    # Windows PowerShell 5.1 returns byte[] for content types it does not know (e.g. application/vnd.spring-boot.actuator.v3+json).
    $content = if ($r.Content -is [byte[]]) { [System.Text.Encoding]::UTF8.GetString($r.Content) } else { [string]$r.Content }
    return [pscustomobject]@{ Code = [int]$r.StatusCode; Body = $content }
  } catch {
    $resp = $_.Exception.Response
    if ($null -eq $resp) { return [pscustomobject]@{ Code = 0; Body = '' } }
    $txt = ''
    try { $sr = New-Object IO.StreamReader($resp.GetResponseStream()); $txt = $sr.ReadToEnd() } catch { }
    return [pscustomobject]@{ Code = [int]$resp.StatusCode; Body = $txt }
  }
}
function J($r) { try { $r.Body | ConvertFrom-Json } catch { $null } }

Write-Host "DIE smoke test  frontend=$front  api=$(if ($api) { $api } else { '<skipped>' })"

if ($api) {
  $r = Req GET "$api/actuator/health/readiness"; Check ($r.Code -eq 200 -and $r.Body -match '"UP"') 'API readiness is UP (Postgres + RabbitMQ)'
  $r = Req GET "$api/actuator/health/liveness";  Check ($r.Code -eq 200 -and $r.Body -match '"UP"') 'API liveness is UP'
}
$r = Req GET "$front/";               Check ($r.Code -eq 200 -and $r.Body -match '<div id="root"') 'frontend serves the SPA shell'
$r = Req GET "$front/some/deep/link"; Check ($r.Code -eq 200 -and $r.Body -match '<div id="root"') 'SPA history fallback (deep link returns index.html)'
$r = Req GET "$front/api/v1/status";  Check ($r.Code -eq 200 -and (J $r).status -eq 'UP') 'GET /api/v1/status through the proxy: UP'
$r = Req GET "$front/api/v1/auth/csrf"
$cookie = $session.Cookies.GetCookies([Uri]$front) | Where-Object { $_.Name -eq 'XSRF-TOKEN' }
Check ($null -ne $cookie) 'CSRF cookie issued through the proxy'

# A mutation WITHOUT the CSRF header must be refused (plain request, no session cookies).
try {
  Invoke-WebRequest -Uri "$front/api/v1/auth/login" -Method POST -UseBasicParsing -ContentType 'application/json' `
    -Body '{"email":"x@x.x","password":"x"}' -TimeoutSec 15 | Out-Null
  Bad 'POST without CSRF header is refused (403)'
} catch {
  $sc = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
  Check ($sc -eq 403) 'POST without CSRF header is refused (403)'
}

$r = Req POST "$front/api/v1/auth/login" @{ email = $email; password = $pass }
Check ($r.Code -eq 200) 'admin login'
if ($r.Code -eq 200) {
  $r = Req GET "$front/api/v1/auth/me"; Check ((J $r).role -eq 'ADMIN') 'session is an ADMIN session'

  $r = Req GET "$front/api/v1/ops/health"; $h = J $r
  Check ($r.Code -eq 200 -and $h.database.status -eq 'UP') 'ops health: database UP'
  Check ($h.broker.status -eq 'UP') 'ops health: broker UP'
  $w = @($h.workers)
  $badWorkers = @($w | Where-Object { $_.status -ne 'UP' -or -not $_.heartbeatFresh })
  Check ($w.Count -ge 2 -and $badWorkers.Count -eq 0) 'ops health: orchestrator and document-worker UP with fresh heartbeats'

  $r = Req POST "$front/api/v1/ops/test-jobs" @{ note = 'smoke'; simulateFailure = $false }
  $job = (J $r).id
  Check ($r.Code -eq 202 -and $job) "test job accepted (202) id=$job request id $rid"

  $state = ''; $corr = ''; $end = (Get-Date).AddSeconds($timeout)
  while ($job -and (Get-Date) -lt $end) {
    $j = J (Req GET "$front/api/v1/ops/test-jobs/$job")
    $state = $j.state; $corr = $j.correlationId
    if ($state -eq 'SUCCEEDED' -or $state -eq 'FAILED') { break }
    Start-Sleep -Seconds 1
  }
  Check ($state -eq 'SUCCEEDED') "test job reached SUCCEEDED (last state: $state)"
  Check ($corr -eq $rid) "request id preserved as the job's correlation id"

  $r = Req POST "$front/api/v1/auth/logout"
  Check (($r.Code -eq 200) -or ($r.Code -eq 204)) 'logout'
}

if ($script:failed) { Write-Host "`nSMOKE TEST FAILED"; exit 1 }
Write-Host "`nSMOKE TEST PASSED  (correlation id: $rid)"; exit 0

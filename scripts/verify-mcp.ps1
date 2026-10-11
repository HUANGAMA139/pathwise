#Requires -Version 5.1
<#
    MCP end-to-end verification  (v27 "must deliver" item #1)
    ---------------------------------------------------------
    Usage (start the app first, in another window):

        mvn spring-boot:run
        powershell -ExecutionPolicy Bypass -File .\scripts\verify-mcp.ps1

    Why this script exists
        At checkpoint 2 we found that the MCP path had not been verified
        end to end since v10 -- and since then the database branch changed
        from mock to real Text2SQL and gained a security layer. The startup
        log line "Registered tools: 3" only proves the tools are REGISTERED,
        not that they are REACHABLE. So this script acts as a plain MCP
        client: handshake, list tools, then actually call the high-level
        tool once. If it passes, "others can connect" is a fact, not a claim.

    Deliberately ASCII-only.
        Windows PowerShell 5.1 reads .ps1 files as the system ANSI codepage
        (GBK on a Chinese Windows) unless the file has a UTF-8 BOM. A UTF-8
        file without BOM gets mis-decoded and can fail to PARSE, not just
        display badly. Keeping every byte ASCII removes that whole class of
        failure. The Chinese response body is written to a UTF-8 file at the
        end, so you can read it in an editor.

    Not yet run in the project environment (no JDK / API key in the sandbox
    where it was written). If step 4 fails, see "Troubleshooting" at the end.
#>

$ErrorActionPreference = "Stop"

$McpUrl   = "http://localhost:8080/mcp"
$HealthUrl = "http://localhost:8080/health"
# The question means "East China last quarter sales", written in Chinese and
# built from code points so that this whole file stays pure ASCII
# (see the encoding note above). One test question, matching eval/testset.csv.
$Question = -join (@(0x534E, 0x4E1C, 0x533A, 0x4E0A, 0x5B63, 0x5EA6,
                     0x9500, 0x552E, 0x989D, 0x662F, 0x591A, 0x5C11) |
                   ForEach-Object { [char]$_ })
$OutFile  = Join-Path $PSScriptRoot "mcp-check-output.txt"

# Protocol revisions to try, oldest-but-widest support first.
# The server negotiates; we just need one it accepts.
$ProtoCandidates = @("2024-11-05", "2025-03-26", "2025-06-18")

$script:fail = 0
$script:proto = $null
$script:sid   = $null

function Head ($m) { Write-Host ""; Write-Host "=== $m ===" -ForegroundColor Cyan }
function Ok   ($m) { Write-Host "  [OK]   $m" -ForegroundColor Green }
function Bad  ($m) { Write-Host "  [FAIL] $m" -ForegroundColor Red; $script:fail++ }
function Note ($m) { Write-Host "  [NOTE] $m" -ForegroundColor Yellow }

# --- one JSON-RPC round trip -------------------------------------------
function Invoke-McpRaw ([string]$body, [string]$sessionId, [string]$protoVer) {
    $headers = @{
        "Content-Type"         = "application/json"
        "Accept"               = "application/json, text/event-stream"
        "MCP-Protocol-Version" = $protoVer
    }
    if ($sessionId) { $headers["Mcp-Session-Id"] = $sessionId }
    return Invoke-WebRequest -Method Post -Uri $McpUrl -Headers $headers `
        -Body ([Text.Encoding]::UTF8.GetBytes($body)) `
        -UseBasicParsing -TimeoutSec 90
}

# --- get the body as text, decoding UTF-8 explicitly -------------------
# Do NOT trust $resp.Content: Windows PowerShell 5.1 decodes the body using
# the charset in Content-Type, and falls back to ISO-8859-1 when the server
# declares none. For a UTF-8 body that silently produces mojibake -- the same
# class of bug as the v29 REST finding, and nothing errors either way. Since
# JSON is UTF-8 by definition (RFC 8259), we decode the raw bytes as UTF-8
# ourselves and sidestep the client's guess entirely.
function Get-ResponseText ($resp) {
    try {
        $ms = $resp.RawContentStream
        if ($ms -and $ms.Length -gt 0) {
            return [Text.Encoding]::UTF8.GetString($ms.ToArray())
        }
    }
    catch { }
    return [string]$resp.Content
}

# --- response may be plain JSON or SSE ("data: {...}" lines) -----------
function ConvertFrom-RpcResponse ($resp) {
    $text = Get-ResponseText $resp
    $json = $null
    foreach ($line in ($text -split "`n")) {
        $t = $line.Trim()
        if ($t.StartsWith("data:")) {
            $payload = $t.Substring(5).Trim()
            if ($payload) { try { $json = $payload | ConvertFrom-Json } catch { } }
        }
    }
    if ($null -eq $json) { $json = $text | ConvertFrom-Json }
    return $json
}

# ======================================================================
Head "0. Is the app up?"

try {
    $health = Invoke-WebRequest -Method Get -Uri $HealthUrl -UseBasicParsing -TimeoutSec 15
    Ok "GET /health -> HTTP $($health.StatusCode)"
}
catch {
    Bad "cannot reach $HealthUrl"
    Write-Host "         start the app first:  mvn spring-boot:run" -ForegroundColor DarkGray
    Write-Host "         $($_.Exception.Message)" -ForegroundColor DarkGray
    exit 1
}

# ======================================================================
Head "1. initialize (handshake)"

foreach ($v in $ProtoCandidates) {
    $initBody = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":' +
                '{"protocolVersion":"' + $v + '","capabilities":{},' +
                '"clientInfo":{"name":"verify-mcp","version":"1.0"}}}'
    try {
        $resp = Invoke-McpRaw $initBody $null $v
        $init = ConvertFrom-RpcResponse $resp
        if ($init.result.serverInfo) {
            $script:proto = $v
            $s = $resp.Headers["Mcp-Session-Id"]
            if ($s -is [array]) { $s = $s[0] }
            $script:sid = $s
            Ok "protocol $v accepted"
            Ok "server: $($init.result.serverInfo.name) $($init.result.serverInfo.version)"
            Note "response Content-Type: $($resp.Headers['Content-Type'])"
            if ($s) { Ok "session id: $s" } else { Note "no Mcp-Session-Id header (stateless mode is fine)" }
            break
        }
        else {
            Note "protocol $v -> response had no serverInfo, trying next"
        }
    }
    catch {
        Note "protocol $v -> $($_.Exception.Message)"
    }
}

if (-not $script:proto) {
    Bad "initialize failed for every candidate protocol ($($ProtoCandidates -join ', '))"
    exit 1
}

# ======================================================================
Head "2. notifications/initialized"

try {
    Invoke-McpRaw '{"jsonrpc":"2.0","method":"notifications/initialized"}' $script:sid $script:proto | Out-Null
    Ok "sent (a notification has no reply, so no response is normal)"
}
catch {
    Note "non-2xx on the notification; some servers ignore it, continuing"
}

# ======================================================================
Head "3. tools/list (tool discovery)"

$toolNames = @()
try {
    $list = ConvertFrom-RpcResponse (Invoke-McpRaw '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' $script:sid $script:proto)
    $toolNames = @($list.result.tools | ForEach-Object { $_.name })
    Write-Host "  found: $($toolNames -join ', ')"

    foreach ($want in @("ask_data_question", "search_documents", "query_database")) {
        if ($toolNames -contains $want) { Ok $want } else { Bad "missing tool: $want" }
    }
}
catch {
    Bad "tools/list failed: $($_.Exception.Message)"
    exit 1
}

# ======================================================================
Head "4. tools/call - ask_data_question (the real end-to-end)"

$callBody = '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":' +
            '{"name":"ask_data_question","arguments":{"question":"' + $Question + '"}}}'

try {
    $call = ConvertFrom-RpcResponse (Invoke-McpRaw $callBody $script:sid $script:proto)

    if ($call.error) {
        Bad "call returned an error: $($call.error | ConvertTo-Json -Compress)"
    }
    else {
        Ok "call returned a result"
        if ($call.result.isError) { Bad "result.isError is true" }

        $text = ""
        if ($call.result.content) {
            $text = ($call.result.content | ForEach-Object { $_.text }) -join "`n"
        }
        if ($text.Length -gt 50) { Ok "response body is non-empty ($($text.Length) chars)" }
        else                     { Bad "response body looks empty ($($text.Length) chars)" }

        [IO.File]::WriteAllText($OutFile, $text, [Text.UTF8Encoding]::new($false))
        Note "full response written to $OutFile (open it in an editor; the console may mangle CJK)"
    }
}
catch {
    Bad "tools/call failed: $($_.Exception.Message)"
}

# ======================================================================
Head "Result"

if ($script:fail -eq 0) {
    Write-Host "  ALL PASSED - the MCP path is genuinely reachable." -ForegroundColor Green
    Write-Host "  -> open $OutFile and confirm it starts with the route decision block,"
    Write-Host "     then a route value (document / database / both / refuse), then the trace."
    Write-Host "  -> after that, the README line 'MCP not verified end to end since v10' can go."
    exit 0
}
else {
    Write-Host "  $($script:fail) check(s) failed." -ForegroundColor Red
    Write-Host ""
    Write-Host "  Troubleshooting:" -ForegroundColor Yellow
    Write-Host "    1. Is the app healthy, not merely started? wait for 'Started ... in N seconds'."
    Write-Host "    2. Handshake rejected for all protocols? add a newer one to `$ProtoCandidates."
    Write-Host "    3. 404 on POST /mcp? check spring.ai.mcp.server.mcp-endpoint in application.yml."
    Write-Host "    4. Still stuck? send me the full console output plus $OutFile."
    exit 1
}

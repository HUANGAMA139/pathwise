#Requires -Version 5.1
<#
    迭代 1 环境自检脚本
    ─────────────────────────────────────────────
    用法（在项目根目录执行）：
        powershell -ExecutionPolicy Bypass -File .\scripts\verify-env.ps1

    逐项检查 迭代 1 的验收标准，告诉你还差什么。
    只读检查，不修改任何东西。

    注意：本脚本刻意避免在插值字符串里嵌套引号，
    因为那在 PowerShell 5.1 和 7+ 之间行为不一致。
#>

$script:fail = 0
$script:warn = 0
$script:dockerOk = $false

$NL = [Environment]::NewLine

function Head ($m) { Write-Host ""; Write-Host "=== $m ===" -ForegroundColor Cyan }
function Ok   ($m) { Write-Host "  [通过] $m" -ForegroundColor Green }
function Bad  ($m) { Write-Host "  [失败] $m" -ForegroundColor Red;    $script:fail++ }
function Warn ($m) { Write-Host "  [提醒] $m" -ForegroundColor Yellow; $script:warn++ }

# 原生调用外部命令，避免 cmd /c 的嵌套引号问题
function Try-Cmd ([scriptblock]$sb) {
    try {
        $out = & $sb 2>&1
        $code = $LASTEXITCODE
        return [pscustomobject]@{
            Ok   = ($code -eq 0 -or $null -eq $code)
            Text = ($out | Out-String).Trim()
        }
    }
    catch {
        return [pscustomobject]@{ Ok = $false; Text = $_.Exception.Message }
    }
}

Write-Host ""
Write-Host "智能数据问答助手 · 迭代 1 环境自检" -ForegroundColor White

# ─────────────────────────────────────────────
Head "1/6  Java 与 Maven"

$j = Try-Cmd { java -version }
if ($j.Ok -and $j.Text) {
    $javaFirst = ($j.Text -split $NL)[0].Trim()
    Ok "java: $javaFirst"
    if ($j.Text -match 'version\s+"(\d+)') {
        $major = [int]$Matches[1]
        if ($major -ge 17) { Ok "JDK 主版本 $major（>= 17，符合要求）" }
        else { Bad "JDK 主版本 $major，本项目需要 17 或更高" }
    }
}
else {
    Bad "找不到 java 命令，检查 PATH"
}

$m = Try-Cmd { mvn -v }
if ($m.Ok -and $m.Text -match 'Apache Maven') {
    $mvnFirst = ($m.Text -split $NL)[0].Trim()
    Ok "maven: $mvnFirst"
}
else {
    Warn "命令行找不到 mvn。如果你用 IDEA 内置 Maven，此项可忽略"
}

# ─────────────────────────────────────────────
Head "2/6  Docker 是否在运行"

$d = Try-Cmd { docker version --format "{{.Server.Version}}" }
if ($d.Ok -and $d.Text -match '^\d+\.') {
    Ok "Docker 引擎在运行，版本 $($d.Text)"
    $script:dockerOk = $true
}
else {
    Bad "Docker 没跑起来。启动 Docker Desktop，等左下角鲸鱼图标变绿后重试"
}

# ─────────────────────────────────────────────
Head "3/6  两个数据库容器"

if (-not $script:dockerOk) {
    Warn "Docker 未就绪，跳过本节"
}
else {
    $ps = Try-Cmd { docker ps --filter name=sdaq- --format "{{.Names}}::{{.Status}}" }
    if (-not $ps.Text -or $ps.Text -notmatch 'sdaq-') {
        Bad "没找到容器。在项目根目录执行： docker compose up -d"
    }
    else {
        $lines = $ps.Text -split $NL | Where-Object { $_.Trim() }
        foreach ($name in @("sdaq-pgvector", "sdaq-business-db")) {
            $prefix = $name + "::"
            $line = $lines | Where-Object { $_.StartsWith($prefix) } | Select-Object -First 1
            if (-not $line) {
                Bad "$name 不存在（执行 docker compose up -d）"
            }
            elseif ($line -match '\(healthy\)') {
                Ok "$name 已就绪"
            }
            else {
                $status = $line.Substring($prefix.Length)
                Warn "$name 状态：$status  —— 等几秒再看，或用 docker compose logs $name"
            }
        }
    }
}

# ─────────────────────────────────────────────
Head "4/6  数据库可用性"

if (-not $script:dockerOk) {
    Warn "Docker 未就绪，跳过本节"
}
else {
    $vectorSql = "select extname from pg_extension where extname='vector'"
    $v = Try-Cmd { docker exec sdaq-pgvector psql -U sdaq -d rag_db -tAc $vectorSql }
    if ($v.Ok -and $v.Text -match 'vector') {
        Ok "rag_db 的 vector 扩展已启用"
    }
    elseif ($v.Ok) {
        Bad "rag_db 连上了，但 vector 扩展没启用。执行 docker compose down -v 后重新 up"
    }
    else {
        Warn "连不上 rag_db（容器可能还没起来）"
    }

    $b = Try-Cmd { docker exec sdaq-business-db psql -U sdaq -d business_db -tAc "select 1" }
    if ($b.Ok -and $b.Text -match '1') {
        Ok "business_db 可连接（现在应为空库，迭代 18 才建表）"
    }
    else {
        Warn "连不上 business_db"
    }
}

# ─────────────────────────────────────────────
Head "5/6  DashScope API Key"

$key = $env:AI_DASHSCOPE_API_KEY
if ([string]::IsNullOrWhiteSpace($key)) {
    Bad "环境变量 AI_DASHSCOPE_API_KEY 没设置"
    Write-Host '         执行： setx AI_DASHSCOPE_API_KEY "sk-你的key"   然后重开终端' -ForegroundColor DarkGray
}
elseif ($key -notlike "sk-*") {
    $head = $key.Substring(0, [Math]::Min(6, $key.Length))
    Warn "变量有值，但不像 DashScope 的 Key（一般以 sk- 开头）：$head****"
}
else {
    $masked = $key.Substring(0, 6) + "****" + $key.Substring($key.Length - 4)
    Ok "已设置：$masked"
}

# ─────────────────────────────────────────────
Head "6/6  冒烟测试"

if ($script:fail -eq 0) {
    Write-Host "  前面都通过了，跑这一条验证 Key 和模型是否真的能通：" -ForegroundColor White
    Write-Host ""
    Write-Host "      mvn spring-boot:run -Dspring-boot.run.arguments=--sdaq.smoke.enabled=true" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "  看到「冒烟测试通过」就算 迭代 1 完成。" -ForegroundColor White
}
else {
    Write-Host "  先解决上面标 [失败] 的项，再来跑冒烟测试。" -ForegroundColor Yellow
}

# ─────────────────────────────────────────────
Write-Host ""
if ($script:fail -eq 0 -and $script:warn -eq 0) {
    Write-Host "总结：全部通过，迭代 1 环境就绪。" -ForegroundColor Green
}
elseif ($script:fail -eq 0) {
    Write-Host "总结：无失败项，有 $($script:warn) 条提醒，可以继续。" -ForegroundColor Yellow
}
else {
    Write-Host "总结：$($script:fail) 项失败、$($script:warn) 条提醒。按上面提示处理后重跑。" -ForegroundColor Red
}
Write-Host ""

# Remplace la base de DEV locale par une copie de prod (export-prod.sh). Poste Windows (PowerShell 5+, client MySQL/MariaDB).
# Usage : .\tools\dev-sync\import-dev.ps1 -Dump <dump.sql.gz> [-Go]      (sans -Go : simulation, affiche la cible)
# Même logique que import-dev.sh : sauvegarde des clés/providers/kill switch locaux -> sauvegarde complète de la base
# locale (backup\dev-avant-import-<ts>.sql) -> DROP/CREATE -> restauration -> clean-dev.sql -> réinjection locale.
param([string]$Dump, [switch]$Go, [string]$Props, [string]$MysqlBin, [string]$RestoreLocal)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = (Resolve-Path (Join-Path $here '..\..')).Path
if (-not $Props) { $Props = Join-Path $root 'src\main\resources\application-dev.properties' }
function Log($m) { Write-Host ("[{0}] {1}" -f (Get-Date -Format HH:mm:ss), $m) }
function Prop($k) { $l = Select-String -Path $Props -Pattern "^\s*$([regex]::Escape($k))\s*=" | Select-Object -Last 1; if ($l) { ($l.Line -split '=', 2)[1].Trim() } }

# Client MySQL : PATH, puis installation MySQL Server 8.0 par défaut.
if (-not $MysqlBin) {
  $c = Get-Command mysql.exe -ErrorAction SilentlyContinue
  $MysqlBin = if ($c) { Split-Path $c.Source } else { 'C:\Program Files\MySQL\MySQL Server 8.0\bin' }
}
$mysql = Join-Path $MysqlBin 'mysql.exe'; $mysqldump = Join-Path $MysqlBin 'mysqldump.exe'
if (-not (Test-Path $mysql)) { throw "mysql.exe introuvable ($MysqlBin) : utiliser -MysqlBin" }
if ($RestoreLocal) { $Dump = $RestoreLocal } elseif (-not $Dump) { throw 'paramètre -Dump requis' }
if (-not (Test-Path $Dump)) { throw "fichier introuvable : $Dump" }

# Cible (application-dev.properties, sinon application.properties) + garde-fous.
$url = Prop 'spring.datasource.url'
if (-not $url) { $url = (Select-String -Path (Join-Path $root 'src\main\resources\application.properties') -Pattern '^\s*spring\.datasource\.url\s*=' | Select-Object -Last 1).Line.Split('=', 2)[1].Trim() }
if ($url -notmatch '^jdbc:mysql://(?:\$\{MYSQL_HOST:)?([^:}/]+)\}?(?::(\d+))?/([^?]+)') { throw "url datasource illisible : $url" }
$dbHost = $Matches[1]; $dbPort = if ($Matches[2]) { $Matches[2] } else { '3306' }; $dbName = $Matches[3]
$dbUser = Prop 'spring.datasource.username'; $env:MYSQL_PWD = Prop 'spring.datasource.password'
if ($dbHost -notin @('localhost', '127.0.0.1')) { throw "refus : hôte '$dbHost' non local" }
if ($dbName -like '*recette*') { throw "refus : base '$dbName' (recette)" }
Log "cible : ${dbUser}@${dbHost}:${dbPort}/${dbName} <- $(Split-Path -Leaf $Dump)"
if (-not $Go) { Log "simulation : relancer avec -Go (EFFACE la base $dbName ; sauvegarde auto dans backup\)"; return }

$tmp = Join-Path ([IO.Path]::GetTempPath()) ("devsync-" + [guid]::NewGuid().ToString('N')); New-Item -ItemType Directory $tmp | Out-Null
function Mysql([string]$inFile, [string]$outFile, [string]$db, [string[]]$extra = @()) {
  # Fichier .cmd temporaire : redirections binaires (pas de ré-encodage PowerShell), pas de piège de guillemets ; mot de passe via MYSQL_PWD.
  $err = Join-Path $tmp 'err.txt'; $bat = Join-Path $tmp 'run.cmd'
  $a = @("-h$dbHost", "-P$dbPort", "-u$dbUser", '--default-character-set=utf8mb4') + $extra
  if ($db) { $a += $db }
  $line = '@"' + $mysql + '" ' + ($a -join ' ') + ' < "' + $inFile + '"' + $(if ($outFile) { ' > "' + $outFile + '"' }) + ' 2> "' + $err + '"'
  [IO.File]::WriteAllText($bat, $line + "`r`n", [Text.Encoding]::Default)
  & cmd.exe /c $bat
  if ($LASTEXITCODE -ne 0) { throw ("mysql en échec : " + (Get-Content $err -Raw -ErrorAction SilentlyContinue)) }
}
function Sql([string]$text, [string]$name) { $f = Join-Path $tmp $name; [IO.File]::WriteAllText($f, $text, (New-Object Text.UTF8Encoding $false)); $f }
try {
  if ($RestoreLocal) {  # remet une sauvegarde locale (backup\dev-avant-import-*.sql) à la place de la base
    Mysql (Sql "DROP DATABASE IF EXISTS ``$dbName``; CREATE DATABASE ``$dbName`` CHARACTER SET utf8mb4;" 'recreate.sql') $null $null
    Mysql $RestoreLocal $null $dbName
    Log "sauvegarde locale restaurée : $RestoreLocal"; return
  }
  # 1) Sauvegarde des éléments locaux (SQL généré ; QUOTE() échappe).
  $keys = Join-Path $tmp 'keys.sql'; $dbExists = $false
  try {
    $q = Sql "SELECT 1 FROM api_credentials LIMIT 1;" 'probe.sql'; Mysql $q (Join-Path $tmp 'probe.out') $dbName; $dbExists = $true
  } catch {
    # Seule une base INEXISTANTE (erreur 1049) autorise à ne rien sauvegarder ; toute autre erreur = abandon, base intacte.
    if ($_.Exception.Message -match '1049|Unknown database|table .* doesn.t exist|1146') { Log "aucune clé locale à sauvegarder (base ou table absente)" } else { throw }
  }
  New-Item -ItemType File $keys | Out-Null
  if ($dbExists) {
    $qk = Sql ("SELECT CONCAT('INSERT INTO _local_keys VALUES (', QUOTE(u.username), ',', QUOTE(p.code), ',', QUOTE(ac.scope), ',', QUOTE(ac.api_key), ',', IFNULL(QUOTE(ac.secret_key),'NULL'), ',', IFNULL(QUOTE(ac.passphrase),'NULL'), ',', ac.enabled+0, ');') FROM api_credentials ac JOIN users u ON u.id=ac.user_id JOIN provider p ON p.id=ac.web_provider_id;`n" +
      "SELECT CONCAT('INSERT INTO _local_providers VALUES (', QUOTE(code), ',', QUOTE(name), ',', IFNULL(QUOTE(api_base_url),'NULL'), ',', enabled+0, ');') FROM provider;`n" +
      "SELECT CONCAT('INSERT INTO _local_kill VALUES (', kill_switch+0, ');') FROM execution_control LIMIT 1;") 'save.sql'
    Mysql $qk $keys $dbName @('-N', '-B', '--raw')
    Log ("éléments locaux sauvegardés : {0} ligne(s)" -f (Get-Content $keys).Count)
    # 2) Sauvegarde complète de la base locale avant effacement.
    $bk = Join-Path $root ("backup\dev-avant-import-{0}.sql" -f (Get-Date -Format yyyyMMdd-HHmmss)); New-Item -ItemType Directory (Split-Path $bk) -Force | Out-Null
    $bat2 = Join-Path $tmp 'dump.cmd'
  [IO.File]::WriteAllText($bat2, ('@"' + $mysqldump + '" -h' + $dbHost + ' -P' + $dbPort + ' -u' + $dbUser + ' --single-transaction --routines --triggers --default-character-set=utf8mb4 ' + $dbName + ' > "' + $bk + '" 2> "' + $tmp + '\dump.err"' + "`r`n"), [Text.Encoding]::Default)
  & cmd.exe /c $bat2
    if ($LASTEXITCODE -ne 0 -or (Get-Item $bk).Length -lt 1000) { throw "sauvegarde de la base locale en échec : abandon (base intacte) " + (Get-Content "$tmp\dump.err" -Raw) }
    Log ("base locale sauvegardée : {0} ({1:N1} Mo)" -f $bk, ((Get-Item $bk).Length / 1MB))
  }
  # 3) Décompression du dump, DROP/CREATE, restauration.
  $sql = Join-Path $tmp 'dump.sql'
  $in = [IO.File]::OpenRead($Dump); $gz = New-Object IO.Compression.GZipStream($in, [IO.Compression.CompressionMode]::Decompress); $out = [IO.File]::Create($sql)
  $gz.CopyTo($out); $out.Close(); $gz.Close(); $in.Close()
  Mysql (Sql "DROP DATABASE IF EXISTS ``$dbName``; CREATE DATABASE ``$dbName`` CHARACTER SET utf8mb4;" 'recreate.sql') $null $null
  Mysql $sql $null $dbName
  Log "dump de prod restauré"
  # 4) Nettoyage puis réinjection locale (une seule session : tables temporaires).
  Mysql (Join-Path $here 'clean-dev.sql') $null $dbName
  $head = "CREATE TEMPORARY TABLE _local_keys (username VARCHAR(20), code VARCHAR(32), scope VARCHAR(8), api_key VARCHAR(2048), secret_key VARCHAR(2048), passphrase VARCHAR(2048), enabled BIT) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;`n" +
          "CREATE TEMPORARY TABLE _local_providers (code VARCHAR(32), name VARCHAR(100), api_base_url VARCHAR(255), enabled BIT) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;`n" +
          "CREATE TEMPORARY TABLE _local_kill (kill_switch BIT) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;`n"
  $re = Sql ($head + [IO.File]::ReadAllText($keys) + "`n" + [IO.File]::ReadAllText((Join-Path $here 'reinject-local.sql'))) 'reinject.sql'
  Mysql $re $null $dbName
  Log "OK : base $dbName = copie de prod nettoyée, clés/providers/kill switch locaux réinjectés. Démarrer le jar (Flyway migre si besoin)."
} finally { Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue; Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }

param(
    [Parameter(Mandatory)][string]$Binding,
    [ValidateSet('inspect','capture','click','close')][string]$Action='inspect',
    [int]$X,[int]$Y,[ValidateSet('left','right')][string]$Button='left',
    [ValidatePattern('^[A-Za-z0-9_-]{1,40}$')][string]$Label='window'
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$b=Get-Content -Raw -LiteralPath $Binding|ConvertFrom-Json
foreach($ref in @($b.java,$b.legacy)) {
    if((Get-FileHash -LiteralPath $ref.path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $ref.sha256) {throw 'Fixed executable/helper changed'}
}
if($b.legacy.sha256 -cne '77818dbe78f63726bc2b823a14351e7f4523b2c88d0cc2506adc0b0a52b49cbe') {throw 'Unqualified window helper'}
$root=Get-Item -LiteralPath $b.output
$ancestor=$root
while($null -ne $ancestor) {
    if(($ancestor.Attributes -band [IO.FileAttributes]::ReparsePoint)-ne 0) {throw 'Linked evidence root'}
    $ancestor=$ancestor.Parent
}
$p=Get-Process -Id $b.process.pid
if($p.Path -ine $b.java.path -or $p.StartTime.ToUniversalTime().Ticks -ne $b.process.startUtcTicks -or
   $p.MainWindowTitle -cne 'RustedWarfare-Reforge | P002 Windows sandbox' -or $p.MainWindowHandle -eq 0) {throw 'Own process identity differs'}
$source=[IO.File]::ReadAllText($b.legacy.path)
$old='RustedWarfare-Reforge | P001 Windows sandbox'
if([regex]::Matches($source,[regex]::Escape($old)).Count -ne 1) {throw 'Fixed helper title shape differs'}
$invoke=@{ProcessId=[int]$b.process.pid;Action=$Action;X=$X;Y=$Y;Button=$Button}
if($Action -eq 'capture') {$invoke.Output=Join-Path $b.output ($Label+'.png')}
& ([scriptblock]::Create($source.Replace($old,'RustedWarfare-Reforge | P002 Windows sandbox'))) @invoke

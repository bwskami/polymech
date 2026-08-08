$src = Join-Path $env:TEMP 'gt_fluids_full.tsv'
$dst = 'C:\modIDEA\polymech\polymech-template-1.21.1\diag\gt_fluids_full.tsv'
Copy-Item $src $dst -Force
Write-Output ("COPIED " + (Get-Item $dst).LastWriteTime)
$lines = Get-Content $dst -Encoding UTF8
Write-Output ("TOTAL LINES: " + $lines.Count)
foreach ($l in $lines) {
    if ($l -match '^(nitric_oxide|nitrogen_dioxide|nitrous_oxide|carbon_monoxide|sulfur_dioxide|ammonium_formate|formaldehyde|formic_acid|ethanol|vinyl_chloride|polyethylene|polytetrafluoroethylene|polyvinyl_chloride|polybenzimidazole|formamide|diethylenetriamine\s|hydrogen_cyanide|steel\s|wrought_iron|annealed_copper|hydrogen_peroxide|hydrofluoric_acid|hydrogen_sulfide)') {
        Write-Output $l
    }
}

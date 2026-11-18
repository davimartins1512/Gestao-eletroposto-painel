from pathlib import Path
import sys
root=Path(sys.argv[1])
src=root/'app/src/main/java/br/com/andreao/fleetprobe'
assert (src/'FleetHomeActivity.java').exists()
home=src/'FleetHomeActivity.java'
h=home.read_text()
marker='    section("ENTREGA E HOMOLOGAÇÃO");'
assert h.count(marker)==1
h=h.replace(marker,'    button("TELEMETRIA REAL BYD · SOC E 12 V",FleetTelemetryActivity.class,\n      "Somente leitura; dados indisponíveis quando o firmware negar acesso");\n'+marker)
home.write_text(h)
accept=src/'FleetAcceptanceActivity.java'
a=accept.read_text()
needle='    r.append("7. MATRIZ DE CAPACIDADES E RESTRIÇÕES\\n");'
assert a.count(needle)==1
a=a.replace(needle,'    r.append("6B. TELEMETRIA OEM REAL (SEM COMANDAR O VEÍCULO)\\n");\n    r.append(FleetTelemetryReadOnly.inspect(context).report()).append("\\n");\n'+needle)
accept.write_text(a)
manifest=root/'app/src/main/AndroidManifest.xml'
m=manifest.read_text()
needle='<activity android:name=".FleetHomeActivity"'
assert m.count(needle)==1
m=m.replace(needle,'<activity android:name=".FleetTelemetryActivity" android:exported="false" />\n    '+needle)
manifest.write_text(m)
gradle=root/'app/build.gradle'
g=gradle.read_text()
assert "versionCode 32; versionName '3.2.0-remote-preview'" in g
g=g.replace("versionCode 32; versionName '3.2.0-remote-preview'", "versionCode 33; versionName '3.3.0-readonly-telemetry'")
gradle.write_text(g)
print("v3.3 READONLY TELEMETRY PATCH APPLIED")

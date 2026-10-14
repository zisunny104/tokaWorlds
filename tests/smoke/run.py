from pathlib import Path
import subprocess, shutil, zipfile, sys, time
import argparse, os
parser=argparse.ArgumentParser(description='獨立 PM1E 世界管理測試；不使用正式資料庫或 Discord Token。')
parser.add_argument('--core',required=True,type=Path)
parser.add_argument('--zero',required=True,type=Path)
parser.add_argument('--one',required=True,type=Path)
parser.add_argument('--portal',required=True,type=Path)
parser.add_argument('--orm',required=True,type=Path,help='含 ormlite-core/jdbc JAR 的資料夾')
parser.add_argument('--sqlite',required=True,type=Path)
parser.add_argument('--jdk',type=Path,default=Path(os.environ.get('JAVA_HOME','')))
args=parser.parse_args()
project=Path(__file__).resolve().parents[2]
root=project/'.build-tools'
(root/'build').mkdir(parents=True,exist_ok=True)
java=args.jdk/'bin'
core=args.core.resolve()
tests=Path(__file__).resolve().parent
worlds=project/'target/tokaWorlds-1.0.0.jar'
classes=root/'build/probe-classes'; classes.mkdir(exist_ok=True)
zero=args.zero.resolve()
one=args.one.resolve()
orm=list(args.orm.resolve().rglob('*.jar'))
sqlite=args.sqlite.resolve()
cp=';'.join(map(str,[core,zero,worlds,*orm]))
subprocess.run([str(java/'javac.exe'),'-encoding','UTF-8','-proc:none','-cp',cp,'-d',str(classes),*[str(tests/f) for f in ['WorldProbe.java','DbLib.java','SmokeLauncher.java']]],check=True)
launcher=root/'build/launcher-classes';launcher.mkdir(exist_ok=True)
subprocess.run([str(java/'javac.exe'),'-encoding','UTF-8','-proc:none','-cp',str(core),'-d',str(launcher),str(tests/'SmokeLauncher.java')],check=True)
def jar(path, entries, descriptor, extra=()):
    with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED) as archive:
        for pattern in entries:
            for p in classes.glob(pattern): archive.write(p,p.relative_to(classes).as_posix())
        archive.writestr('plugin.yml',descriptor)
        for lib in extra:
            with zipfile.ZipFile(lib) as source:
                for entry in source.infolist():
                    if entry.filename.startswith('META-INF/') or entry.is_dir():continue
                    archive.writestr(entry,source.read(entry))
run=root/'build'/('smoke-'+'portal'+'-'+time.strftime('%H%M%S')); (run/'plugins').mkdir(parents=True)
shutil.copy2(zero,run/'plugins/zero.jar')
shutil.copy2(worlds,run/'plugins/tokaWorlds.jar')
if 'portal'!='no-deps':
    shutil.copy2(one,run/'plugins/one.jar')
    (run/'plugins/one').mkdir()
    (run/'plugins/one/config.yml').write_text('token: invalid-test-token\nmsg-channel-id: ""\ncmd-channel-id: ""\n',encoding='utf-8')
if 'portal' in ('database','portal'):
    jar(run/'plugins/DbLib-fixture.jar',['ru/nukkit/dblib/*.class'],'name: DbLib\nversion: "test-fixture"\nmain: ru.nukkit.dblib.DbLib\napi: ["1.0.4"]\nload: POSTWORLD\n',[*orm,sqlite])
if 'portal'=='portal':shutil.copy2(args.portal.resolve(),run/'plugins/tokaPortal.jar')
jar(run/'plugins/Probe.jar',['WorldProbe*.class'],'name: WorldProbe\nversion: "1"\nmain: WorldProbe\napi: ["1.0.4"]\nload: POSTWORLD\nsoftdepend: [zero, one, tokaPortal, tokaWorlds]\n')
(run/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=19242\nlevel-name=test\nlevel-type=void\nmax-players=1\nview-distance=1\nxbox-auth=off\nauto-save=off\nenable-query=off\nallow-nether=off\nallow-the-end=off\n',encoding='utf-8')
with (run/'console.log').open('wb') as output:
    process=subprocess.Popen([str(java/'java.exe'),'-Xms128m','-Xmx512m','-Dfile.encoding=UTF-8','-Dterminal.jline=false','-Dterminal.ansi=false','-Dprobe.database='+str('portal' in ('database','portal')).lower(),'-cp',str(launcher)+';'+str(core),'SmokeLauncher'],cwd=run,stdout=output,stderr=subprocess.STDOUT,stdin=subprocess.DEVNULL)
    print('Test directory:',run,flush=True)
    try: process.wait(timeout=120)
    except subprocess.TimeoutExpired: process.kill();process.wait();raise RuntimeError('Server test timed out')
log=(run/'logs/server.log' if (run/'logs/server.log').exists() else run/'console.log').read_text(encoding='utf-8',errors='replace')
for line in log.splitlines():
    if any(x in line for x in ['WORLD_','模組','ERROR','停用','側邊','NoClass','Exception']): print(line)
if 'WORLD_SMOKE_PASS' not in log or 'WORLD_SMOKE_FAIL' in log:sys.exit(1)

"""Build an isolated instrumentation APK consuming the supplied production AAR."""
import argparse
import os
from pathlib import Path
import subprocess
import zipfile


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--sdk', type=Path, required=True)
parser.add_argument('--ndk', default='25.2.9519653')
parser.add_argument('--build-tools', default='35.0.0')
parser.add_argument('--aar', type=Path, required=True)
parser.add_argument('--abi', choices=['arm64-v8a', 'armeabi-v7a'], required=True)
parser.add_argument('--out', type=Path, required=True)
args = parser.parse_args()
source = Path(__file__).resolve().parent
out = args.out.resolve()
out.mkdir(parents=True, exist_ok=True)
tools = args.sdk / 'build-tools' / args.build_tools
android_jar = args.sdk / 'platforms/android-34/android.jar'
java = Path(os.environ['JAVA_HOME']) / 'bin'
native = out / 'native'
run('cmake', '-S', source, '-B', native, '-G', 'Ninja',
    '-DCMAKE_TOOLCHAIN_FILE=' + str(args.sdk / 'ndk' / args.ndk / 'build/cmake/android.toolchain.cmake'),
    '-DANDROID_ABI=' + args.abi, '-DANDROID_PLATFORM=android-21',
    '-DANDROID_STL=c++_static', '-DCMAKE_BUILD_TYPE=Release')
run('cmake', '--build', native, '-j', '4')
with zipfile.ZipFile(args.aar) as aar:
    (out / 'pine-classes.jar').write_bytes(aar.read('classes.jar'))
classes = out / 'classes'
classes.mkdir(exist_ok=True)
run(java / 'javac', '-source', '8', '-target', '8', '-cp',
    os.pathsep.join([str(out / 'pine-classes.jar'), str(android_jar)]), '-d', classes,
    source / 'InitializationTest.java', source / 'RegressionInstrumentation.java')
run(java / 'jar', 'cf', out / 'test-classes.jar', '-C', classes, '.')
run(java / 'java', '-cp', tools / 'lib/d8.jar', 'com.android.tools.r8.D8',
    '--min-api', '21', '--lib', android_jar, '--output', out / 'tests.jar',
    out / 'pine-classes.jar', out / 'test-classes.jar')
run(tools / 'aapt2', 'link', '-I', android_jar, '--manifest', source / 'AndroidManifest.xml',
    '-o', out / 'unsigned.apk')
with zipfile.ZipFile(out / 'unsigned.apk', 'a') as apk:
    with zipfile.ZipFile(out / 'tests.jar') as dex:
        apk.writestr('classes.dex', dex.read('classes.dex'))
    with zipfile.ZipFile(args.aar) as aar:
        for name in aar.namelist():
            if name.startswith('jni/' + args.abi + '/') and name.endswith('.so'):
                apk.writestr('lib/' + name[4:], aar.read(name))
    apk.write(native / 'libpine_init_probe.so', 'lib/' + args.abi + '/libpine_init_probe.so')
run(tools / 'zipalign', '-f', '4', out / 'unsigned.apk', out / 'aligned.apk')
key = out / 'test.keystore'
if not key.exists():
    run(java / 'keytool', '-genkeypair', '-keystore', key, '-alias', 'pine-test',
        '-storepass', 'android', '-keypass', 'android', '-keyalg', 'RSA', '-validity', '30',
        '-dname', 'CN=Pine Initialization Regression', '-noprompt')
run(java / 'java', '-jar', tools / 'lib/apksigner.jar', 'sign', '--ks', key,
    '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', out / 'pine-test.apk', out / 'aligned.apk')
print(out / 'pine-test.apk')

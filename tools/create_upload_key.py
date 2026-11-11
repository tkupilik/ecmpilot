"""Create a first-release upload key and ignored local Gradle signing settings.

Never replaces an existing key/configuration or prints its generated password.
The keystore password is passed to keytool through an environment variable.
"""
import argparse
import os
from pathlib import Path
import secrets
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--keytool', help='Path to JDK keytool; defaults to JAVA_HOME or PATH')
    args = parser.parse_args()
    keytool = args.keytool or (
        str(Path(os.environ['JAVA_HOME']) / 'bin' / ('keytool.exe' if os.name == 'nt' else 'keytool'))
        if os.environ.get('JAVA_HOME') else shutil.which('keytool')
    )
    if not keytool or not Path(keytool).is_file():
        parser.error('Set JAVA_HOME or pass --keytool with the JDK executable path.')

    directory = ROOT / 'signing'
    directory.mkdir(exist_ok=True)
    key = directory / 'ecmpilot-upload.p12'
    config = directory / 'release.properties'
    certificate = directory / 'ecmpilot-upload-cert.pem'
    if any(path.exists() for path in (key, config, certificate)):
        parser.error('Signing files already exist. Existing keys and settings are never overwritten.')

    password = secrets.token_urlsafe(36)
    environment = dict(os.environ, ECMPILOT_UPLOAD_PASSWORD=password)
    generated = subprocess.run([
        keytool, '-genkeypair', '-noprompt', '-storetype', 'PKCS12',
        '-keystore', str(key), '-alias', 'ecmpilot-upload',
        '-keyalg', 'RSA', '-keysize', '3072', '-sigalg', 'SHA256withRSA',
        '-validity', '10000', '-dname', 'CN=eCMPilot upload, O=eCMPilot, C=CZ',
        '-storepass:env', 'ECMPILOT_UPLOAD_PASSWORD',
        '-keypass:env', 'ECMPILOT_UPLOAD_PASSWORD',
    ], env=environment, capture_output=True, text=True)
    if generated.returncode:
        raise SystemExit('keytool did not create the key. No existing signing files were replaced.')

    # Keep recovery settings before further work: an export failure must not lose
    # the generated password needed to reopen a successfully created keystore.
    with config.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(
            '# LOCAL SECRET - back up securely; never commit or publish.\n'
            'storeFile=signing/ecmpilot-upload.p12\n'
            f'storePassword={password}\nkeyAlias=ecmpilot-upload\nkeyPassword={password}\n'
        )
    result = subprocess.run([
        keytool, '-exportcert', '-rfc', '-keystore', str(key),
        '-alias', 'ecmpilot-upload', '-storepass:env', 'ECMPILOT_UPLOAD_PASSWORD',
        '-file', str(certificate),
    ], env=environment, capture_output=True, text=True)
    if result.returncode:
        raise SystemExit('The key/settings were saved, but public certificate export failed. Keep signing/ for recovery.')
    print('Created upload key, local signing settings and public PEM certificate in signing/.')
    print('Back up the keystore and release.properties securely. No password has been printed.')


if __name__ == '__main__':
    main()

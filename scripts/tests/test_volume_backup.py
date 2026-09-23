"""Docker integration tests; only UUID-prefixed disposable volumes are touched."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
import uuid
import time

spec = importlib.util.spec_from_file_location('backup', Path(__file__).parents[1] / 'volume-backup.py')
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)


class BackupTests(unittest.TestCase):
    def setUp(self):
        self.source = 'backup40-test-' + uuid.uuid4().hex[:12]
        self.target = self.source + '-restored'
        self.temp = tempfile.TemporaryDirectory()
        self.folder = Path(self.temp.name) / 'archive'
        self.container = None
        for volume in b.names(self.source).values():
            b.output('volume', 'create', volume)
            b.output('run', '--rm', '--network', 'none', '--entrypoint', 'sh',
                     '--mount', 'type=volume,src=' + volume + ',dst=/data', b.IMAGE,
                     '-c', 'mkdir /data/nested; printf "sample-40" > /data/nested/item; chmod 640 /data/nested/item')

    def tearDown(self):
        if self.container:
            b.output('rm', '-f', self.container)
        existing = set(b.output('volume', 'ls', '-q').split())
        for volume in list(b.names(self.source).values()) + list(b.names(self.target).values()):
            if volume in existing:
                b.output('volume', 'rm', volume)
        self.temp.cleanup()

    def test_roundtrip_and_existing_target_refusal(self):
        b.backup(self.folder, self.source)
        b.restore(self.folder, self.target)
        for volume in b.names(self.target).values():
            result = b.output('run', '--rm', '--network', 'none', '--entrypoint', 'sh',
                              '--mount', 'type=volume,src=' + volume + ',dst=/data,readonly', b.IMAGE,
                              '-c', 'cat /data/nested/item; stat -c " %a" /data/nested/item')
            self.assertEqual('sample-40 640', result)
        with self.assertRaisesRegex(ValueError, 'already exists'):
            b.restore(self.folder, self.target)

    def test_mysql_can_start_and_read_after_restore(self):
        source = b.names(self.source)['mysql_data']
        b.output('run', '--rm', '--network', 'none', '--entrypoint', 'sh',
                 '--mount', 'type=volume,src=' + source + ',dst=/data', b.IMAGE,
                 '-c', 'rm -r /data/nested')
        def start_mysql(volume):
            self.container = b.output('run', '-d', '--network', 'none',
                '-e', 'MYSQL_ROOT_PASSWORD=lesson40-isolated-test',
                '--mount', 'type=volume,src=' + volume + ',dst=/var/lib/mysql', 'mysql:8.4')
            for _ in range(75):
                result = subprocess.run(['docker', 'exec', '-e', 'MYSQL_PWD=lesson40-isolated-test',
                    self.container, 'mysql', '-uroot', '-Nse', 'SELECT 1'], capture_output=True)
                # The official image first uses a temporary initialization server.
                if result.returncode == 0 and 'MySQL init process done' in b.output('logs', self.container):
                    return
                # A restored data directory does not run initialization again.
                if result.returncode == 0 and volume != source:
                    return
                time.sleep(1)
            self.fail('Isolated MySQL did not become ready')
        start_mysql(source)
        b.output('exec', '-e', 'MYSQL_PWD=lesson40-isolated-test', self.container,
                 'mysql', '-uroot', '-e', "CREATE DATABASE lesson40; CREATE TABLE lesson40.sample(id INT); INSERT INTO lesson40.sample VALUES (40)")
        b.output('stop', '-t', '60', self.container)
        b.backup(self.folder, self.source)
        b.output('rm', self.container); self.container = None
        b.restore(self.folder, self.target)
        start_mysql(b.names(self.target)['mysql_data'])
        result = b.output('exec', '-e', 'MYSQL_PWD=lesson40-isolated-test', self.container,
                          'mysql', '-uroot', '-Nse', 'SELECT id FROM lesson40.sample')
        self.assertEqual('40', result)

    def test_active_source_refused(self):
        volume = b.names(self.source)['mysql_data']
        self.container = b.output('run', '-d', '--network', 'none', '--entrypoint', 'sleep',
                                  '--mount', 'type=volume,src=' + volume + ',dst=/data', b.IMAGE, '120')
        with self.assertRaisesRegex(ValueError, 'Stop all'):
            b.backup(self.folder, self.source)
        self.assertFalse(self.folder.exists())

    def test_corruption_refused_before_target_creation(self):
        b.backup(self.folder, self.source)
        with (self.folder / 'qdrant_data.tar.gz').open('ab') as stream:
            stream.write(b'corrupt')
        with self.assertRaisesRegex(ValueError, 'integrity'):
            b.restore(self.folder, self.target)
        self.assertFalse(set(b.names(self.target).values()).intersection(b.output('volume', 'ls', '-q').split()))


if __name__ == '__main__':
    unittest.main()

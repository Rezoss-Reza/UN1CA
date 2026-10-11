#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('update', Path(__file__).resolve().parents[1] / 'update.py')
update = importlib.util.module_from_spec(spec)
spec.loader.exec_module(update)


class AuthenticationTests(unittest.TestCase):
    def setUp(self):
        update.github_token.cache_clear()
        self.addCleanup(update.github_token.cache_clear)

    def test_environment_takes_priority(self):
        with patch.dict(update.os.environ, {'GH_TOKEN': 'test-env'}, clear=True), patch.object(update.subprocess, 'run') as run:
            self.assertEqual(update.github_token(), 'test-env')
        run.assert_not_called()

    def test_saved_login_is_cached(self):
        result = update.subprocess.CompletedProcess([], 0, 'test-login\n', '')
        with patch.dict(update.os.environ, {}, clear=True), patch.object(update.subprocess, 'run', return_value=result) as run:
            self.assertEqual(update.github_token(), 'test-login')
            self.assertEqual(update.github_token(), 'test-login')
        run.assert_called_once()

    def test_missing_cli_allows_unauthenticated_requests(self):
        with patch.dict(update.os.environ, {}, clear=True), patch.object(update.subprocess, 'run', side_effect=FileNotFoundError):
            self.assertIsNone(update.github_token())

    def test_token_only_sent_to_github_api_on_stdin(self):
        with patch.object(update, 'github_token', return_value='test-secret'), patch.object(update.subprocess, 'run') as run:
            update.fetch('https://api.github.com/repos/example', Path('/tmp/example'))
            args, kwargs = run.call_args
            self.assertNotIn('test-secret', ' '.join(args[0]))
            self.assertIn('test-secret', kwargs['input'])
            update.fetch('https://nightly.link/example', Path('/tmp/example'))
            self.assertEqual(run.call_args.kwargs['input'], '')


class CacheTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.cache = Path(self.temp.name)
        self.original = 'kernel=5.15.220\nksu-next-CI=#2071\n'
        (self.cache / 'version').write_text(self.original)
        for name in update.ARTIFACTS:
            (self.cache / (name + '.zip')).write_bytes(b'old')

    def metadata(self, path, scratch):
        if '/releases?' in path:
            return [{'assets': [{'name': 'common-5.15.221-test.zip', 'browser_download_url': 'kernel'}]}]
        if '/runs?' in path:
            return {'workflow_runs': [{'id': 123, 'run_number': 2072}]}
        return {'artifacts': [{'name': name, 'id': index, 'expired': False,
                              'archive_download_url': name} for index, name in enumerate(update.ARTIFACTS)]}

    def download(self, url, output):
        output.write_bytes(b'new')

    def test_offline_keeps_version_and_payloads(self):
        with patch.object(update, 'api', side_effect=OSError('offline')):
            update.refresh(self.cache)
        self.assertEqual((self.cache / 'version').read_text(), self.original)
        for name in update.ARTIFACTS:
            self.assertEqual((self.cache / (name + '.zip')).read_bytes(), b'old')

    def test_new_versions_commit_validated_payloads(self):
        with patch.object(update, 'api', side_effect=self.metadata), patch.object(update, 'fetch', side_effect=self.download), patch.object(update, 'validate'):
            update.refresh(self.cache)
        self.assertEqual((self.cache / 'version').read_text(), 'kernel=5.15.221\nksu-next-CI=#2072\n')
        for name in update.ARTIFACTS:
            self.assertEqual((self.cache / (name + '.zip')).read_bytes(), b'new')

    def test_second_ci_payload_failure_preserves_pair_and_ci_version(self):
        def validate(path, member):
            if member.endswith('ksud'):
                raise ValueError('bad download')
        with patch.object(update, 'api', side_effect=self.metadata), patch.object(update, 'fetch', side_effect=self.download), patch.object(update, 'validate', side_effect=validate):
            update.refresh(self.cache)
        self.assertEqual((self.cache / 'version').read_text(), 'kernel=5.15.221\nksu-next-CI=#2071\n')
        for name in update.ARTIFACTS:
            self.assertEqual((self.cache / (name + '.zip')).read_bytes(), b'old')

    def test_equal_versions_do_not_download(self):
        def metadata(path, scratch):
            if '/releases?' in path:
                return [{'assets': [{'name': 'common-5.15.220-test.zip'}]}]
            return {'workflow_runs': [{'id': 123, 'run_number': 2071}]}
        with patch.object(update, 'api', side_effect=metadata), patch.object(update, 'fetch') as fetch:
            update.refresh(self.cache)
        fetch.assert_not_called()
        self.assertEqual((self.cache / 'version').read_text(), self.original)

    def test_wrong_architecture_rejected(self):
        import zipfile
        payload = self.cache / 'wrong.zip'
        with zipfile.ZipFile(payload, 'w') as archive:
            archive.writestr('android13-5.15_kernelsu.ko', b'not an ARM64 ELF module')
        with self.assertRaises(ValueError):
            update.validate(payload, 'android13-5.15_kernelsu.ko')

    def test_commit_failure_rolls_back(self):
        payload = self.cache / 'new-payload'
        payload.write_bytes(b'new')
        replace = update.os.replace
        def fail_version(source, destination):
            if Path(destination).name == 'version':
                raise OSError('version write failed')
            return replace(source, destination)
        name = next(iter(update.ARTIFACTS)) + '.zip'
        with patch.object(update.os, 'replace', side_effect=fail_version), self.assertRaises(OSError):
            update.commit(self.cache, {name: payload}, 'kernel=5.15.221\nksu-next-CI=#2072\n')
        self.assertEqual((self.cache / name).read_bytes(), b'old')
        self.assertEqual((self.cache / 'version').read_text(), self.original)


if __name__ == '__main__':
    unittest.main()

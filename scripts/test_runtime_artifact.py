import tempfile
from pathlib import Path
import unittest
import zipfile
from runtime_artifact import prepare, digest


class RuntimeArtifactTest(unittest.TestCase):
    def jar(self, path, content):
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('static/admin/index.html', content)

    def test_rebuilding_source_keeps_prior_runtime_resources(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); source = root / 'build.jar'
            self.jar(source, 'old'); first, checksum = prepare(source, root / 'runtime')
            self.jar(source, 'new'); second, _ = prepare(source, root / 'runtime')
            self.assertNotEqual(first, second); self.assertEqual(digest(first), checksum)
            with zipfile.ZipFile(first) as archive:
                self.assertEqual(archive.read('static/admin/index.html'), b'old')

    def test_corrupt_existing_copy_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); source = root / 'build.jar'; self.jar(source, 'valid')
            target, _ = prepare(source, root / 'runtime'); target.chmod(0o600); target.write_bytes(b'broken')
            with self.assertRaises(ValueError): prepare(source, root / 'runtime')
            self.assertEqual(target.read_bytes(), b'broken')

    def test_symlink_source_and_runtime_directory_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); source = root / 'build.jar'; self.jar(source, 'valid')
            link = root / 'linked.jar'; link.symlink_to(source)
            with self.assertRaises(ValueError): prepare(link, root / 'runtime')
            directory = root / 'linked-dir'; directory.symlink_to(root, target_is_directory=True)
            with self.assertRaises(ValueError): prepare(source, directory)


if __name__ == '__main__': unittest.main()

import os
from pathlib import Path
import tempfile
import unittest
import json
from room_load_paths import validated_sources, digest_snapshot, verify_snapshot


class LoadSnapshotPathsTest(unittest.TestCase):
    def test_trailing_empty_entry_does_not_include_current_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            classes = root / 'module/target/test-classes'
            classes.mkdir(parents=True)
            self.assertEqual([classes.resolve()], validated_sources(str(classes) + os.pathsep, root))

    def test_explicit_workspace_or_external_directory_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for path in [root, root.parent]:
                with self.assertRaises(ValueError):
                    validated_sources(str(path), root)

    def test_missing_or_wholly_empty_classpath_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for value in ['', os.pathsep, str(root / 'missing.jar')]:
                with self.assertRaises(ValueError):
                    validated_sources(value, root)

    def test_reuse_rejects_changed_dependency_and_external_classpath(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot = root / '.local/room-storage-snapshot-20261106T200000000000Z'
            dependency = snapshot / 'lib/example.jar'
            dependency.parent.mkdir(parents=True)
            dependency.write_bytes(b'compiled-dependency')
            sha256, files = digest_snapshot(snapshot)
            (snapshot / 'manifest.json').write_text(json.dumps(dict(sha256=sha256, files=files, classpathEntries=1)))
            (snapshot / 'classpath.txt').write_text(str(dependency))
            self.assertEqual(sha256, verify_snapshot(snapshot, root)['sha256'])
            dependency.write_bytes(b'changed')
            with self.assertRaises(ValueError):
                verify_snapshot(snapshot, root)
            (snapshot / 'classpath.txt').write_text(str(root))
            with self.assertRaises(ValueError):
                verify_snapshot(snapshot, root)


if __name__ == '__main__':
    unittest.main()

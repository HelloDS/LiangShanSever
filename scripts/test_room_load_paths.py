import os
from pathlib import Path
import tempfile
import unittest
from room_load_paths import validated_sources


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


if __name__ == '__main__':
    unittest.main()

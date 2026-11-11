import os
from pathlib import Path
import tempfile
import time
import unittest
from urllib.error import HTTPError
from urllib.request import urlopen
from neo2_vision.preview import PreviewServer


class PreviewTests(unittest.TestCase):
    def test_local_feed_requires_token_and_rejects_stale_frames(self):
        with tempfile.TemporaryDirectory() as directory:
            generation = 'a' * 24
            path = Path(directory) / generation / 'hls'
            path.mkdir(parents=True)
            image = path / 'preview.jpg'
            image.write_bytes(b'\xff\xd8test\xff\xd9')
            preview = PreviewServer(directory)
            url = preview.start()
            try:
                preview.update({'connection': {'generation': generation}})
                with urlopen(url, timeout=2) as response:
                    self.assertIn(b'Neo 2 live view', response.read())
                with urlopen(url + 'frame', timeout=2) as response:
                    self.assertEqual(response.read(), image.read_bytes())
                    self.assertEqual(response.headers['Cache-Control'], 'no-store')
                with self.assertRaises(HTTPError) as missing:
                    urlopen(url.rsplit('/', 2)[0] + '/wrong/frame', timeout=2)
                self.assertEqual(missing.exception.code, 404)
                missing.exception.close()
                old = time.time() - 10
                os.utime(image, (old, old))
                with self.assertRaises(HTTPError) as stale:
                    urlopen(url + 'frame', timeout=2)
                self.assertEqual(stale.exception.code, 503)
                stale.exception.close()
            finally:
                preview.close()

    def test_invalid_generation_cannot_escape_root(self):
        preview = PreviewServer('/tmp')
        preview.update({'connection': {'generation': '../../etc'}})
        with self.assertRaises(FileNotFoundError):
            preview.frame()

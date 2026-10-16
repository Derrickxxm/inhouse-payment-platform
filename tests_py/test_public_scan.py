import base64
import os
import subprocess
import sys
import tempfile
import unittest

SCRIPT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "scripts", "public_scan.py")
DENY = base64.b64decode("RmludXAs5Yeh5pmuLOa/gOWFieS7mCxMYXNlclBheSxtZXJjZWRlc2JlbnoscHVodWks5a6d5LuYLOaYk+WunSzpgJrogZQs5a+M5Y+LLOmTtuiBlCzlhYjplIs=").decode().split(",")


def run_scan(files):
    with tempfile.TemporaryDirectory() as d:
        subprocess.run(["git", "init", "-q", d], check=True)
        for name, content in files.items():
            p = os.path.join(d, name)
            os.makedirs(os.path.dirname(p), exist_ok=True)
            with open(p, "w", encoding="utf-8") as f:
                f.write(content)
        subprocess.run(["git", "-C", d, "add", "-A"], check=True)
        r = subprocess.run([sys.executable, SCRIPT, d], capture_output=True, text=True)
        return r.returncode, r.stdout + r.stderr


class PublicScanTest(unittest.TestCase):
    def test_clean_repo_passes(self):
        code, out = run_scan({"a.txt": "hello world version 1.2.3\n"})
        self.assertEqual(code, 0, out)

    def test_ipv4_fails(self):
        ip = ".".join(["10", "1", "2", "3"])
        code, out = run_scan({"a.txt": "host " + ip + "\n"})
        self.assertEqual(code, 1, out)
        self.assertIn("a.txt:1", out)

    def test_non_example_email_fails(self):
        mail = "bob" + "@" + "corp-mail" + ".com"
        code, _ = run_scan({"a.txt": mail + "\n"})
        self.assertEqual(code, 1)

    def test_allowed_email_domains_pass(self):
        domains = ["example.com", "example.org", "example.net", "foo.example",
                   "bar.invalid", "users.noreply.github.com"]
        text = "\n".join("u" + "@" + d for d in domains) + "\n"
        code, out = run_scan({"a.txt": text})
        self.assertEqual(code, 0, out)

    def test_every_deny_term_fails_case_insensitively(self):
        self.assertEqual(len(DENY), 12)
        for term in DENY:
            code, out = run_scan({"a.txt": "x " + term.upper() + " y\n"})
            self.assertEqual(code, 1, term)

    def test_nested_files_are_scanned(self):
        code, _ = run_scan({"src/deep/b.txt": "ip " + ".".join(["192", "168", "0", "1"]) + "\n"})
        self.assertEqual(code, 1)

    def test_untracked_files_are_ignored(self):
        with tempfile.TemporaryDirectory() as d:
            subprocess.run(["git", "init", "-q", d], check=True)
            with open(os.path.join(d, "a.txt"), "w") as f:
                f.write(".".join(["10", "0", "0", "9"]) + "\n")
            r = subprocess.run([sys.executable, SCRIPT, d], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_binary_files_are_skipped(self):
        with tempfile.TemporaryDirectory() as d:
            subprocess.run(["git", "init", "-q", d], check=True)
            with open(os.path.join(d, "x.bin"), "wb") as f:
                f.write(b"\xff\xfe\x00\x80" + b"\xff" * 10)
            subprocess.run(["git", "-C", d, "add", "-A"], check=True)
            r = subprocess.run([sys.executable, SCRIPT, d], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)


if __name__ == "__main__":
    unittest.main()

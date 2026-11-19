"""No provider access: same-byte local replace and real Windows handle contention."""
import ctypes
from ctypes import wintypes
import os
from pathlib import Path
import tempfile
import threading
import time
import unittest

from replan_durable_write import replace_with_retry


class DurableWriteTest(unittest.TestCase):
    def test_success_never_rewrites_source_bytes_or_repeats_other_work(self):
        calls=[];clock=[0.]
        def replace(source,destination):
            calls.append((source,destination))
            if len(calls)<3:
                error=PermissionError("synthetic sharing collision");error.winerror=5;raise error
        result=replace_with_retry(Path("same-temp"),Path("same-ledger"),replace=replace,
            clock=lambda:clock[0],sleep=lambda seconds:clock.__setitem__(0,clock[0]+seconds))
        self.assertEqual(calls,[(Path("same-temp"),Path("same-ledger"))]*3)
        self.assertEqual(result["attempts"],3)
        self.assertAlmostEqual(result["elapsedSeconds"],.03)

    def test_permanent_windows_denial_stops_within_time_and_attempt_bounds(self):
        calls=[];clock=[0.]
        def replace(*paths):
            calls.append(paths);error=PermissionError("persistent ACL or sharing denial");error.winerror=5;raise error
        with self.assertRaises(PermissionError):
            replace_with_retry(Path("temp"),Path("ledger"),max_wait_seconds=.05,replace=replace,
                clock=lambda:clock[0],sleep=lambda seconds:clock.__setitem__(0,clock[0]+seconds))
        self.assertLessEqual(clock[0],.05)
        self.assertEqual(len(calls),3)

    def test_unrelated_filesystem_error_is_never_retried(self):
        calls=[]
        def replace(*paths):calls.append(paths);raise OSError("synthetic disk failure")
        with self.assertRaises(OSError):replace_with_retry(Path("temp"),Path("ledger"),replace=replace)
        self.assertEqual(len(calls),1)

    @unittest.skipUnless(os.name=="nt","requires actual Windows sharing semantics")
    def test_real_windows_open_handle_denies_then_allows_same_atomic_replace(self):
        api=ctypes.WinDLL("kernel32",use_last_error=True)
        api.CreateFileW.argtypes=[wintypes.LPCWSTR,wintypes.DWORD,wintypes.DWORD,ctypes.c_void_p,wintypes.DWORD,wintypes.DWORD,wintypes.HANDLE]
        api.CreateFileW.restype=wintypes.HANDLE
        api.CloseHandle.argtypes=[wintypes.HANDLE];api.CloseHandle.restype=wintypes.BOOL
        with tempfile.TemporaryDirectory(prefix="replan-d-io-") as temp:
            folder=Path(temp).resolve();source=folder/"same.tmp";destination=folder/"ledger.json"
            old=b'{"calls":0}\n';new=b'{"calls":1,"unicode":"exact bytes"}\n'
            source.write_bytes(new);destination.write_bytes(old)
            handle=api.CreateFileW(str(destination),0x80000000,0x1|0x2,None,3,0,None)
            if handle==wintypes.HANDLE(-1).value:raise ctypes.WinError(ctypes.get_last_error())
            released=threading.Event()
            def release():
                time.sleep(.12);api.CloseHandle(handle);released.set()
            thread=threading.Thread(target=release);thread.start()
            try:
                with self.assertRaises(OSError):os.replace(source,destination)
                self.assertEqual(destination.read_bytes(),old);self.assertEqual(source.read_bytes(),new)
                result=replace_with_retry(source,destination)
                self.assertGreater(result["attempts"],1)
                self.assertEqual(destination.read_bytes(),new);self.assertFalse(source.exists())
            finally:
                thread.join(timeout=2)
                if not released.is_set():api.CloseHandle(handle)


if __name__=="__main__":unittest.main(verbosity=2)

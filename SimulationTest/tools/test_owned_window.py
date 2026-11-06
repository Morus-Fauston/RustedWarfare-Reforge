"""Controlled native capture buffers and ownership; no actual PC screenshot/input."""
from pathlib import Path
import tempfile
import types
import sys
import unittest
from unittest.mock import patch

from owned_window import Guard,GuardFailure,Win32,focus_owned_once


class OwnedCaptureTest(unittest.TestCase):
    def test_initial_focus_uses_one_verified_owned_header_click_and_never_retries(self):
        base=dict(pid=11,startTicks=22,path='owned-java',hwnd=33,title='owned',windowThreadId=44,
            alive=True,visible=True,foreground=99,width=100,height=80,x=10,y=20,
            keyboardLayout=0x12340409,qualifiedKeyboardLayout=0x12340409,keyboardLanguage=0x409)
        for fault in ('none','occluded','cursor','held','focus'):
            with self.subTest(fault=fault):
                state=dict(base);sent=[];point=[0,0]
                def send(kind,value,up):
                    sent.append((kind,value,up))
                    if up and fault!='focus':state['foreground']=33
                api=types.SimpleNamespace(snapshot=lambda:dict(state),point_owned=lambda x,y:fault!='occluded',
                    pressed=lambda kind,value:fault=='held',move=lambda x,y:point.__setitem__(slice(None),[x,y]),
                    cursor=lambda:(0,0) if fault=='cursor' else tuple(point),send=send)
                if fault=='none':focus_owned_once(api,base,lambda row:None,sleep=lambda n:None)
                else:
                    with self.assertRaises(GuardFailure):focus_owned_once(api,base,lambda row:None,sleep=lambda n:None)
                self.assertEqual(sent,[('mouse','left',False),('mouse','left',True)] if fault in ('none','focus') else [])

    def test_an_occluded_client_is_refused_before_reading_another_windows_pixels(self):
        state=dict(pid=11,startTicks=22,path='owned-java',hwnd=33,title='owned',windowThreadId=44,
            alive=True,visible=True,foreground=33,width=100,height=80,x=10,y=20,
            keyboardLayout=0x12340409,qualifiedKeyboardLayout=0x12340409,keyboardLanguage=0x409)
        def unexpected_capture(box):self.fail('Occlusion must be refused before capturing desktop pixels')
        api=types.SimpleNamespace(snapshot=lambda:dict(state),point_owned=lambda x,y:x<100,capture=unexpected_capture)
        with tempfile.TemporaryDirectory(prefix='owned-occlusion-') as temporary:
            path=Path(temporary)/'refused.png'
            with self.assertRaises(GuardFailure):Guard(api,state,lambda row:None).capture(path)
            self.assertFalse(path.exists())

    def test_secondary_monitor_capture_requests_the_virtual_desktop(self):
        box=(1900,20,2000,100)
        def grab(bbox,all_screens=False):
            return 'owned-secondary-buffer' if bbox==box and all_screens else 'primary-only-black-buffer'
        pillow=types.ModuleType('PIL');pillow.ImageGrab=types.SimpleNamespace(grab=grab)
        with patch.dict(sys.modules,{'PIL':pillow}):
            self.assertEqual('owned-secondary-buffer',Win32.capture(object(),box))

    def test_black_or_wrong_size_capture_is_preserved_and_rejected(self):
        state=dict(pid=11,startTicks=22,path='owned-java',hwnd=33,title='owned',windowThreadId=44,
            alive=True,visible=True,foreground=33,width=100,height=80,x=10,y=20,
            keyboardLayout=0x12340409,qualifiedKeyboardLayout=0x12340409,keyboardLanguage=0x409)
        class Bitmap:
            mode='RGB'
            def __init__(self,fault):self.size=(99,80) if fault=='size' else (100,80);self.fault=fault
            def getextrema(self):return ((0,0),(0,0),(0,0)) if self.fault=='black' else ((0,255),(0,128),(0,64))
            def save(self,path):Path(path).write_bytes(b'owned controlled native buffer, not an actual PNG screenshot')
        for fault in ('none','black','size'):
            with self.subTest(fault=fault),tempfile.TemporaryDirectory(prefix='owned-capture-') as temporary:
                api=types.SimpleNamespace(snapshot=lambda:dict(state),point_owned=lambda x,y:True,capture=lambda box:Bitmap(fault))
                guard=Guard(api,state,lambda row:None);path=Path(temporary)/'capture.png'
                if fault=='none':guard.capture(path)
                else:
                    with self.assertRaises(GuardFailure):guard.capture(path)
                self.assertTrue(path.is_file(),'An owned failed capture remains available for diagnosis')


if __name__=='__main__':unittest.main()

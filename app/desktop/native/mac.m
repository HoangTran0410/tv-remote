// What a Cocoa app gives its window and webview does not, for TV Remote's window (built with webview's core by build.sh):
// the menu bar (without an Edit menu Cmd+C / Cmd+V do nothing in text fields) and a window that keeps its size and place.
// The page asks its questions itself (ask() in core.js), so WebKit's alert / confirm / prompt are not needed.
// The microphone: the 🎤 key turns speech into text in WebKit (remote.js); macOS asks the person once (Info.plist's
// NSMicrophoneUsageDescription / NSSpeechRecognitionUsageDescription), and WebKit then asks the window, answered below.
#import <Cocoa/Cocoa.h>
#import <WebKit/WebKit.h>
#import <objc/runtime.h>
#include "webview/webview.h"

static BOOL vi(void) { return [[NSLocale preferredLanguages].firstObject hasPrefix:@"vi"]; }
static NSString *T(NSString *v, NSString *e) { return vi() ? v : e; }

static NSMenuItem *item(NSMenu *m, NSString *title, SEL action, NSString *key) {
    return [m addItemWithTitle:title action:action keyEquivalent:key];
}

static void menus(void) {
    NSMenu *bar = [NSMenu new];
    NSMenu *app = [NSMenu new];
    item(app, T(@"Ẩn TV Remote", @"Hide TV Remote"), @selector(hide:), @"h");
    [app addItem:[NSMenuItem separatorItem]];
    item(app, T(@"Thoát TV Remote", @"Quit TV Remote"), @selector(performClose:), @"q");   // the window's close: the server stops properly
    NSMenu *edit = [[NSMenu alloc] initWithTitle:T(@"Sửa", @"Edit")];
    item(edit, T(@"Hoàn tác", @"Undo"), @selector(undo:), @"z");
    item(edit, T(@"Làm lại", @"Redo"), @selector(redo:), @"Z");
    [edit addItem:[NSMenuItem separatorItem]];
    item(edit, T(@"Cắt", @"Cut"), @selector(cut:), @"x");
    item(edit, T(@"Sao chép", @"Copy"), @selector(copy:), @"c");
    item(edit, T(@"Dán", @"Paste"), @selector(paste:), @"v");
    item(edit, T(@"Chọn tất cả", @"Select All"), @selector(selectAll:), @"a");
    NSMenu *view = [[NSMenu alloc] initWithTitle:T(@"Xem", @"View")];
    item(view, T(@"Tải lại", @"Reload"), @selector(reload:), @"r");
    NSMenuItem *full = item(view, T(@"Toàn màn hình", @"Enter Full Screen"), @selector(toggleFullScreen:), @"f");
    full.keyEquivalentModifierMask = NSEventModifierFlagCommand | NSEventModifierFlagControl;
    NSMenu *win = [[NSMenu alloc] initWithTitle:T(@"Cửa sổ", @"Window")];
    item(win, T(@"Thu nhỏ", @"Minimize"), @selector(performMiniaturize:), @"m");
    item(win, T(@"Phóng to", @"Zoom"), @selector(performZoom:), @"");
    item(win, T(@"Đóng", @"Close"), @selector(performClose:), @"w");
    for (NSMenu *m in @[app, edit, view, win]) [bar addItemWithTitle:@"" action:nil keyEquivalent:@""].submenu = m;
    NSApp.mainMenu = bar;
    NSApp.windowsMenu = win;
}

static BOOL ours(WKSecurityOrigin *o) { return [o.host isEqualToString:@"127.0.0.1"] || [o.host isEqualToString:@"localhost"]; }

// webview's own WKUIDelegate class answers neither question, and unanswered they are a no: the methods are added to it.
// Only this app's own page (its server on 127.0.0.1) gets the microphone or the camera, never a page it was sent to.
static void microphone(void) {
    Class c = objc_getClass("WebviewWKUIDelegate");
    if (!c) return;
    class_addMethod(c, sel_registerName("webView:requestMediaCapturePermissionForOrigin:initiatedByFrame:type:decisionHandler:"),
        imp_implementationWithBlock(^(id self, WKWebView *v, WKSecurityOrigin *o, WKFrameInfo *f, NSInteger type, void (^done)(NSInteger)) {
            done(ours(o) && (type == 0 || type == 1) ? 1 : 2);       // Camera (Video on the TV) or Microphone → Grant, else Deny
        }), "v@:@@@q@?");
    class_addMethod(c, sel_registerName("_webView:requestSpeechRecognitionPermissionForOrigin:decisionHandler:"),   // WebKit's own (no public one)
        imp_implementationWithBlock(^(id self, WKWebView *v, WKSecurityOrigin *o, void (^done)(BOOL)) { done(ours(o)); }), "v@:@@@?");
}

void tvr_mac_setup(webview_t w) {
    NSWindow *win = (__bridge NSWindow *)webview_get_window(w);
    menus();
    microphone();
    win.minSize = NSMakeSize(360, 480);
    [win center];
    win.frameAutosaveName = @"TV Remote";                             // the size and place it had last time
}

"""Finish the guest screen lock on a services.jar built before change 7.

guest_local_lock.py gained two pieces after the first LineageOS services.jar
was built: the check in change 2 hands out OneOSGuestLock.HANDLE in its
VerifyCredentialResponse, and change 7 answers that handle in
LockSettingsService.verifyGatekeeperPasswordHandle with a hardware auth token.
Without them Settings' face enrollment asks for a token, gets no handle, and
the setup wizard bounces between "set up your face" and "enter your device PIN"
("LockSettingsService: No gatekeeper password for handle").

The jar is already patched, so guest_local_lock.py refuses it ("already has the
guest screen lock"). This patcher upgrades that jar in place:

1. OneOSGuestLock.smali is replaced with the checked-in helper, which is the
   same record format plus HANDLE and hat(JI)[B.
2. In oneosGuestLockVerify, the matched branch's `VerifyCredentialResponse.OK`
   becomes a response carrying HANDLE.
3. verifyGatekeeperPasswordHandle gets the change 7 hook at its entry.

Nothing else changes. A jar that already has all three is left alone.

Usage: guest_local_lock_hat.py <smali root dir>
Prints the smali_* directory it changed, one per line.
"""
import os
import sys

PKG = os.path.join("com", "android", "server", "locksettings")
LSS_REL = os.path.join(PKG, "LockSettingsService.smali")
HELPER_REL = os.path.join(PKG, "OneOSGuestLock.smali")
HERE = os.path.dirname(os.path.abspath(__file__))
HELPER_SRC = os.path.join(HERE, "OneOSGuestLock.smali")

VCR = "Lcom/android/internal/widget/VerifyCredentialResponse;"
BUILDER = VCR[:-1] + "$Builder;"
HELPER = "Lcom/android/server/locksettings/OneOSGuestLock;"
MARK = "oneosGuestLockVerify"
GK_LABEL = "oneos_gl_gk_native"
OK_LINE = ("    sget-object v7, " + VCR + "->OK:" + VCR)

MATCHED = f"""\
    # OneOS: the guest lock's gatekeeper-password handle: Settings turns it into an
    # enrollment token (change 7). No handle or token of the phone's gatekeeper exists
    # behind it (patches/guest_local_lock.py).
    new-instance v7, {BUILDER}

    invoke-direct {{v7}}, {BUILDER}-><init>()V

    const-wide v0, 0x4f4e454f53L

    invoke-virtual {{v7, v0, v1}}, {BUILDER}->setGatekeeperPasswordHandle(J){BUILDER}

    move-result-object v7

    invoke-virtual {{v7}}, {BUILDER}->build(){VCR}

    move-result-object v7""".split("\n")

GK_HOOK = f"""\
    # OneOS: the handle a matched guest credential hands out has no gatekeeper password of the
    # phone behind it; answer it with the token the enrollment only carries along, and leave
    # every other handle to the framework (patches/guest_local_lock.py).
    invoke-static {{p1, p2, p5}}, {HELPER}->hat(JI)[B

    move-result-object v0

    if-eqz v0, :oneos_gl_gk_native

    new-instance v1, {BUILDER}

    invoke-direct {{v1}}, {BUILDER}-><init>()V

    invoke-virtual {{v1, v0}}, {BUILDER}->setGatekeeperHAT([B){BUILDER}

    move-result-object v1

    invoke-virtual {{v1}}, {BUILDER}->build(){VCR}

    move-result-object v1

    return-object v1

    :oneos_gl_gk_native""".split("\n")


def fail(msg):
    sys.exit("guest_local_lock_hat: " + msg)


def main(root):
    dirs = [d for d in sorted(os.listdir(root))
            if d.startswith("smali") and os.path.isfile(os.path.join(root, d, LSS_REL))]
    if len(dirs) != 1:
        fail(f"{len(dirs)} files {LSS_REL}")
    d = dirs[0]
    lss = os.path.join(root, d, LSS_REL)
    helper = os.path.join(root, d, HELPER_REL)
    if not os.path.isfile(helper):
        fail(f"no {HELPER_REL} in {d}")
    if not os.path.isfile(HELPER_SRC):
        fail(f"no helper at {HELPER_SRC}")

    lines = open(lss).read().split("\n")
    text = "\n".join(lines)
    if MARK not in text:
        fail(f"{LSS_REL} has no guest screen lock; run guest_local_lock.py instead")

    have_hat = "->hat(JI)[B" in text
    have_gk = GK_LABEL in text
    with open(HELPER_SRC) as f:
        helper_new = f.read().split("\n")
    helper_old = open(helper).read().split("\n")
    helper_changed = helper_new != helper_old
    if have_hat and have_gk and not helper_changed:
        print("guest_local_lock_hat: this services.jar already answers the handle",
              file=sys.stderr)
        return

    # 1. The helper, with HANDLE and hat().
    if helper_changed:
        with open(helper, "w") as f:
            f.write("\n".join(helper_new))

    # 2. The matched branch of oneosGuestLockVerify hands out the handle.
    if not have_hat:
        starts = [i for i, l in enumerate(lines)
                  if l.startswith(".method private oneosGuestLockVerify(")]
        if len(starts) != 1:
            fail(f"oneosGuestLockVerify found {len(starts)} times")
        i = starts[0]
        end = next(k for k in range(i, len(lines)) if lines[k] == ".end method")
        hits = [k for k in range(i, end) if lines[k] == OK_LINE]
        if len(hits) != 1:
            fail(f"oneosGuestLockVerify has {len(hits)} matched-branch OK returns")
        lines[hits[0]:hits[0] + 1] = MATCHED

    # 3. The change 7 hook at the entry of verifyGatekeeperPasswordHandle.
    if not have_gk:
        starts = [i for i, l in enumerate(lines)
                  if l.startswith(".method public verifyGatekeeperPasswordHandle(JJI)")]
        if len(starts) != 1:
            fail(f"verifyGatekeeperPasswordHandle found {len(starts)} times")
        i = starts[0]
        j = i + 2
        while lines[j].strip() in ("", ".end param") or lines[j].strip().startswith(".param"):
            j += 1
        # .line directives are fine: the hook runs before the permission check.
        lines[j:j] = GK_HOOK

    text = "\n".join(lines)
    for marker in ("->hat(JI)[B", GK_LABEL, "->setGatekeeperPasswordHandle(J)"):
        if marker not in text:
            fail(f"the upgrade left no {marker}")
    with open(lss, "w") as f:
        f.write(text)
    print(d)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: guest_local_lock_hat.py <smali root dir>")
    main(sys.argv[1])

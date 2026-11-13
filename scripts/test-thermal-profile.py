#!/usr/bin/env python3
"""Check the production thermal profile map and child-shell vote handoff."""

from pathlib import Path
import shutil
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]
SWITCH = (ROOT / "switcher/assets/oneos_switch.sh").read_text()


class ThermalProfiles(unittest.TestCase):
    def test_profile_values_match_the_documented_modes(self):
        shell = shutil.which("sh")
        if shell is None:
            self.skipTest("POSIX shell is unavailable")
        start = SWITCH.index("performance_profile_values() {")
        end = SWITCH.index("\n}", start) + 2
        function = SWITCH[start:end]
        expected = {
            "balanced": "45 47 50 80 65 50",
            "smoother": "47 50 53 90 80 65",
            "maximum": "0 0 0 0 0 0",
        }
        for name, values in expected.items():
            with self.subTest(name=name):
                result = subprocess.run(
                    [shell, "-c", f'{function}\nperformance_profile_values "{name}"'],
                    check=True,
                    capture_output=True,
                    text=True,
                )
                self.assertEqual(values, result.stdout.strip())
        invalid = subprocess.run(
            [shell, "-c", f'{function}\nperformance_profile_values "turbo"'],
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(0, invalid.returncode)

    def test_host_power_state_reaches_the_thermal_worker_child(self):
        assignment = SWITCH.index("HOST_LOW_POWER=$(timeout 3 settings get global low_power")
        export = SWITCH.index("export HOST_LOW_POWER", assignment)
        worker = SWITCH.index("$HOST_LOW_POWER", SWITCH.index("F=$STAGE/heat_cap.log"))
        self.assertLess(assignment, export)
        self.assertLess(export, worker)
        release = SWITCH.index("echo -1 >", worker)
        self.assertLess(worker, release)


if __name__ == "__main__":
    unittest.main()

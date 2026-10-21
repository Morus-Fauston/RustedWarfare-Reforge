import unittest
from platform_perf import surface_frames
from perf_report import stats

class PerfAnalysisTest(unittest.TestCase):
    def test_nearest_rank_literal_and_empty(self):
        self.assertEqual(dict(n=5,p50=30,p95=50,max=50),stats([50,10,30,20,40]))
        self.assertEqual(dict(n=0,p50=None,p95=None,max=None),stats([]))
    def test_actual_presents_exclude_unfinished_slots(self):
        result=surface_frames('16666667\n0 0 0\n10 20 30\n20 40 50\n0 9223372036854775807 0\n')
        self.assertEqual([20,40],result['presentNanos']); self.assertEqual(2,result['excludedRows'])
    def test_empty_duplicate_or_malformed_never_qualifies(self):
        for raw in ('','16666667\n0 0 0\n','16666667\n1 2 3\n4 2 6\n','16666667\n1 2\n'):
            with self.assertRaises(AssertionError): surface_frames(raw)

if __name__=='__main__':unittest.main()

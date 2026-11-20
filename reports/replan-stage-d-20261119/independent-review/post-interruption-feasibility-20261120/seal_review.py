"""Seal only this independent review, using existing source and metadata."""
from __future__ import annotations

import csv
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
OUT = Path(__file__).resolve().parent


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return json.loads(path.read_bytes())


def save(name, value):
    with (OUT / name).open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write('\n')


def main():
    names = ('analyze_feasibility.py', 'sensitivity.json', 'sensitivity.csv',
             'source-and-draft-review.json', 'review_loopback_metadata.py',
             'loopback-concept-review.json', 'review_real_java_metadata.py',
             'real-java-evidence-review.json', 'REVIEW.md', 'seal_review.py')
    assert {path.name for path in OUT.iterdir()} == set(names)
    sensitivity = read(OUT / 'sensitivity.json')
    source_review = read(OUT / 'source-and-draft-review.json')
    loopback = read(OUT / 'loopback-concept-review.json')
    java_probe = read(OUT / 'real-java-evidence-review.json')
    assert len(sensitivity['scenarios']) == 180
    with (OUT / 'sensitivity.csv').open(encoding='utf-8-sig', newline='') as stream:
        rows = list(csv.DictReader(stream))
    assert len(rows) == 180
    for actual, expected in zip(rows, sensitivity['scenarios']):
        assert actual == {key: str(value) for key, value in expected.items()}
    assert source_review['rootCheck2SixtyScenariosIndependentlyMatched']
    assert all(row['gitOriginalBytesMatch'] for row in source_review['frozenJava'])
    sources = dict(source_review['sources'])
    for additions in (loopback['sourceHashes'], java_probe['sourceHashes'],
                      {row['path']: row['sha256'] for row in source_review['jars']}):
        for name, digest in additions.items():
            assert name not in sources or sources[name] == digest
            sources[name] = digest
    assert all(sha(ROOT / name) == digest for name, digest in sources.items())
    assert not loopback['productionAccepted'] and not loopback['newPaidEvaluationAuthorized']
    assert loopback['suppliedScenarios'] == 8 and loopback['suppliedAssertionsTrue'] == 9
    assert loopback['proposalReview']['scopeConcrete']
    assert java_probe['suppliedAssertionsTrue'] == 16 and not java_probe['productionAccepted']
    artifacts = {name: sha(OUT / name) for name in names}
    save('source-evidence-manifest.json', dict(
        format='independent-post-interruption-feasibility-manifest-v1',
        sourceFiles=sources, independentArtifacts=artifacts,
        sourceFilesVerifiedAtSeal=True, artifactFiles=len(artifacts)))
    save('conclusion.json', dict(
        format='independent-post-interruption-feasibility-conclusion-v1',
        disposition='Budget arithmetic and scoped conceptual feasibility accepted; production integration not accepted',
        evidenceManifestSha256=sha(OUT / 'source-evidence-manifest.json'),
        engineeringAccepted=False, paidAuthorized=False, canExecute=False,
        rootBudgetDraftV2ScenariosMatched=60, independentSensitivityScenarios=180,
        frozenJavaOriginalsMatched=6, frozenJarsMatched=2,
        loopbackSuppliedScenarios=8, loopbackSuppliedAssertions=9,
        loopbackSuppliedWallMs='4212.920', loopbackIndependentNetworkReruns=0,
        loopbackCurrentSourceMatchesCheck3=True,
        currentBusinessAnswerDeadlinePreservationProvenByScaledPoc=False,
        commonLateKnownAndCandidateUnknownCompatibility='source-level conditional only; no production monitor run',
        futureRouteScope=dict(newQuestions=902, newMaximumApplicationCalls=1804,
                              futureFMaximumCalls=1004, futureBMaximumCalls=800,
                              cumulativeQuestions=1046, cumulativeMaximumApplicationCalls=1950),
        proposedCumulativeLimitYuan='10.00', proposedLimitGuaranteesCompletion=False,
        proposedScopeConcrete=True, proposedScopeIsHumanApproval=False,
        requiredBlockers=[row['id'] for row in loopback['blockers']],
        realJavaSyntheticScenarioMetadataAndOriginalsReviewed=True,
        realJavaSyntheticAssertions=16, realJavaHttpWallMs='15104.216',
        realJavaInternalBudgetElapsedMs=15001, realJavaModelElapsedMs=14902,
        realJavaIndependentReruns=0, realJavaMonitorTlsPeakResourceIntegrated=False,
        actualBatchReadOrWritten=False, old68AnswersOrSqlParsed=False,
        unseenRead=False, providerCalls=0, keyReads=0, paidCostAddedYuan='0',
        productionMonitorInvoked=False, historicalUnknownsReleased=False,
        interruptedBatchReplayAllowed=False, fullDevAccuracyOrP95Claimed=False))
    print(json.dumps(dict(artifacts=12, sourceBindings=len(sources),
                          manifestSha256=sha(OUT / 'source-evidence-manifest.json'),
                          conclusionSha256=sha(OUT / 'conclusion.json'),
                          paidAuthorized=False, engineeringAccepted=False)))


if __name__ == '__main__':
    main()

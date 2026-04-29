import re

with open(r'D:\Programs\Java\Java Project\Bunq\frontend\src\pages\LaunchDetailPage.tsx', 'r', encoding='utf-8') as f:
    code = f.read()

# 1. Update StripLiveLabel
old_strip = '''function StripLiveLabel({ launchId, code, onDone }: { launchId: string; code: string; onDone: () => void }) {
  const { currentStage, lastEvent } = useJurisdictionStream(launchId, code, { onDone });
  const ordinal = lastEvent?.type === 'stage.started' ? lastEvent.ordinal : undefined;
  const total = lastEvent?.type === 'stage.started' ? lastEvent.totalStages : undefined;
  const label = currentStage
    ? `${currentStage}${ordinal != null && total != null ? ` ${ordinal}/${total}` : ''}`
    : 'In progress';
  return (
    <span className="mono-label" style={{ animation: 'ldPulse 1.5s ease-in-out infinite' }}>
      {label}
    </span>
  );
}'''
new_strip = '''function StripLiveLabel({ currentStage, lastEvent }: { currentStage: string | null; lastEvent: any }) {
  const ordinal = lastEvent?.type === 'stage.started' ? lastEvent.ordinal : undefined;
  const total = lastEvent?.type === 'stage.started' ? lastEvent.totalStages : undefined;
  const label = currentStage
    ? `${currentStage}${ordinal != null && total != null ? ` ${ordinal}/${total}` : ''}`
    : 'In progress';
  return (
    <span className="mono-label" style={{ animation: 'ldPulse 1.5s ease-in-out infinite' }}>
      {label}
    </span>
  );
}'''
code = code.replace(old_strip, new_strip)

# 2. Update JurisdictionLiveIndicator
old_live = '''function JurisdictionLiveIndicator({
  launchId,
  code,
  onDone,
}: {
  launchId: string;
  code: string;
  onDone: () => void;
}) {
  const { currentStage, status, lastEvent } = useJurisdictionStream(launchId, code, { onDone });

  if (status === 'closed') return null;'''
new_live = '''function JurisdictionLiveIndicator({
  currentStage,
  status,
  lastEvent,
}: {
  currentStage: string | null;
  status: string;
  lastEvent: any;
}) {
  if (status === 'closed') return null;'''
code = code.replace(old_live, new_live)

# 3. Update RunDetailSection
old_rundetail = '''function RunDetailSection({
  run,
  launchId,
  code,
  isRunning,
  actionItems,
  onDone,
  onDownload,
  onOpenGraph,
}: {
  run: JurisdictionRun;
  launchId: string;
  code: string;
  isRunning: boolean;
  actionItems: string[];
  onDone: () => void;
  onDownload: () => void;
  onOpenGraph: () => void;
}) {
  const { auditEvents, status } = useJurisdictionStream(launchId, code, {
    enabled: isRunning,
    onDone,
  });'''
new_rundetail = '''function RunDetailSection({
  run,
  isRunning,
  actionItems,
  onDownload,
  onOpenGraph,
  auditEvents,
  status,
  currentStage,
  lastEvent,
}: {
  run: JurisdictionRun;
  isRunning: boolean;
  actionItems: string[];
  onDownload: () => void;
  onOpenGraph: () => void;
  auditEvents: AuditEvent[];
  status: string;
  currentStage: string | null;
  lastEvent: any;
}) {'''
code = code.replace(old_rundetail, new_rundetail)

# 4. Update the JSX inside RunDetailSection
code = code.replace('''          <JurisdictionLiveIndicator
            launchId={launchId}
            code={code}
            onDone={onDone}
          />''', '''          <JurisdictionLiveIndicator
            currentStage={currentStage}
            status={status}
            lastEvent={lastEvent}
          />''')

# 5. Extract JurisdictionRow
old_map = '''              {filtered.map((run) => {
                const code = run.jurisdictionCode;
                const iso3 = ISO2_TO_ISO3[code] ?? code;
                const isSelected = iso3 === selectedIso3;
                const key = runStatusKey(run);
                const isRunning = run.status === 'RUNNING' || run.status === 'PENDING';

                const defaultSummary = [
                  `Gaps ${run.gapsCount}`,
                  `Sanctions hits ${run.sanctionsHits}`,
                  run.lastRunAt ? `Last run ${new Date(run.lastRunAt).toLocaleDateString()}` : '',
                ].filter(Boolean).join(' · ');

                const countsLine = `Obligations: ${run.obligationsCount ?? 0} • Controls: ${run.controlsCount ?? 0} • Gaps: ${run.gapsCount ?? 0}`;

                const actionItems =
                  run.verdict === 'RED'
                    ? (run.blockers ?? []).slice(0, 5)
                    : (run.requiredChanges ?? []).slice(0, 5);

                return (
                  <div
                    key={code}
                    className={`fjp__row fjp__row--${key} fjp__row--open`}
                    ref={(el) => {
                      if (el) rowRefs.current.set(code, el);
                      else rowRefs.current.delete(code);
                    }}
                    style={isSelected ? { borderColor: 'var(--orange)' } : undefined}
                  >
                    <div className="fjp__row-head fjp__row-head--static">
                      <span className="fjp__row-flag">{jurisdictionFlag(code)}</span>
                      <span className="fjp__row-name">{jurisdictionLabel(code)}</span>
                      <span
                        className={`fjp__row-status fjp__row-status--${key}`}
                        title={statusTooltip(run, key, isRunning)}
                      >
                        <span className="fjp__row-status-dot" />
                        {/* StripLiveLabel replaces static "In progress" with live SSE stage name */}
                        {isRunning
                          ? <StripLiveLabel launchId={id!} code={code} onDone={refetch} />
                          : statusLabelForRun(run, key, false)}
                      </span>
                      <span className="fjp__row-summary" style={{ display: 'inline-flex', flexDirection: 'column', alignItems: 'flex-start', gap: 2 }}>
                        <span>{run.summary ?? defaultSummary}</span>
                        <span style={{ fontSize: 11, color: 'rgba(255,255,255,0.55)', fontFamily: 'var(--mono)' }}>
                          {countsLine}
                        </span>
                      </span>
                      <button
                        className="fjp__deselect"
                        onClick={() => navigate(`/jurisdictions/${code}/launches/${id}`)}
                        title="Open graph"
                        aria-label="Open graph"
                      >
                        <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                          <line x1="5" y1="12" x2="19" y2="12" />
                          <polyline points="12 5 19 12 12 19" />
                        </svg>
                      </button>
                    </div>

                    <RunDetailSection
                      run={run}
                      launchId={id!}
                      code={code}
                      isRunning={isRunning}
                      actionItems={actionItems}
                      onDone={refetch}
                      onDownload={() => downloadProofPack(id!, code)}
                      onOpenGraph={() => navigate(`/jurisdictions/${code}/launches/${id}`)}
                    />
                  </div>
                );
              })}'''

new_map = '''              {filtered.map((run) => (
                <JurisdictionRow 
                  key={run.jurisdictionCode} 
                  run={run} 
                  launchId={id!} 
                  selectedIso3={selectedIso3} 
                  rowRefs={rowRefs} 
                  refetch={refetch} 
                  navigate={navigate} 
                />
              ))}'''

code = code.replace(old_map, new_map)

# Add JurisdictionRow definition before RunDetailSection
row_def = '''function JurisdictionRow({ run, launchId, selectedIso3, rowRefs, refetch, navigate }: any) {
  const code = run.jurisdictionCode;
  const iso3 = ISO2_TO_ISO3[code] ?? code;
  const isSelected = iso3 === selectedIso3;
  const key = runStatusKey(run);
  const isRunning = run.status === 'RUNNING' || run.status === 'PENDING';

  const defaultSummary = [
    `Gaps ${run.gapsCount}`,
    `Sanctions hits ${run.sanctionsHits}`,
    run.lastRunAt ? `Last run ${new Date(run.lastRunAt).toLocaleDateString()}` : '',
  ].filter(Boolean).join(' · ');

  const countsLine = `Obligations: ${run.obligationsCount ?? 0} • Controls: ${run.controlsCount ?? 0} • Gaps: ${run.gapsCount ?? 0}`;

  const actionItems =
    run.verdict === 'RED'
      ? (run.blockers ?? []).slice(0, 5)
      : (run.requiredChanges ?? []).slice(0, 5);

  const { auditEvents, status, currentStage, lastEvent } = useJurisdictionStream(launchId, code, {
    enabled: isRunning,
    onDone: refetch,
  });

  return (
    <div
      className={`fjp__row fjp__row--${key} fjp__row--open`}
      ref={(el) => {
        if (el) rowRefs.current.set(code, el);
        else rowRefs.current.delete(code);
      }}
      style={isSelected ? { borderColor: 'var(--orange)' } : undefined}
    >
      <div className="fjp__row-head fjp__row-head--static">
        <span className="fjp__row-flag">{jurisdictionFlag(code)}</span>
        <span className="fjp__row-name">{jurisdictionLabel(code)}</span>
        <span
          className={`fjp__row-status fjp__row-status--${key}`}
          title={statusTooltip(run, key, isRunning)}
        >
          <span className="fjp__row-status-dot" />
          {isRunning
            ? <StripLiveLabel currentStage={currentStage} lastEvent={lastEvent} />
            : statusLabelForRun(run, key, false)}
        </span>
        <span className="fjp__row-summary" style={{ display: 'inline-flex', flexDirection: 'column', alignItems: 'flex-start', gap: 2 }}>
          <span>{run.summary ?? defaultSummary}</span>
          <span style={{ fontSize: 11, color: 'rgba(255,255,255,0.55)', fontFamily: 'var(--mono)' }}>
            {countsLine}
          </span>
        </span>
        <button
          className="fjp__deselect"
          onClick={() => navigate(`/jurisdictions/${code}/launches/${launchId}`)}
          title="Open graph"
          aria-label="Open graph"
        >
          <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
            <line x1="5" y1="12" x2="19" y2="12" />
            <polyline points="12 5 19 12 12 19" />
          </svg>
        </button>
      </div>

      <RunDetailSection
        run={run}
        isRunning={isRunning}
        actionItems={actionItems}
        onDownload={() => downloadProofPack(launchId, code)}
        onOpenGraph={() => navigate(`/jurisdictions/${code}/launches/${launchId}`)}
        auditEvents={auditEvents}
        status={status}
        currentStage={currentStage}
        lastEvent={lastEvent}
      />
    </div>
  );
}

// ── Run detail section — owns its own SSE hook instance ──────────────────────
'''

code = code.replace('// ── Run detail section — owns its own SSE hook instance ──────────────────────\n', row_def)

with open(r'D:\Programs\Java\Java Project\Bunq\frontend\src\pages\LaunchDetailPage.tsx', 'w', encoding='utf-8') as f:
    f.write(code)
print('Successfully refactored LaunchDetailPage.tsx')

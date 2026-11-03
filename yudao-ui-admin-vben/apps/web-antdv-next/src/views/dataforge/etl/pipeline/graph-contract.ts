/** 画布端口与执行端口分离：兼容旧图 out / properties.outputPort。 */
export function getOutputPorts(
  toolCode?: string,
  config: Record<string, any> = {},
) {
  if (toolCode === 'sub-pipeline')
    return (config.outputs ?? []).map(
      (port: { name: string }) => port.name,
    ) as string[];
  if (toolCode === 'boundary-output') return [] as string[];
  return toolCode === 'quality-check' && config.disposition !== 'KEEP_TAGGED'
    ? ['pass', 'warn', 'reject']
    : ['main'];
}

export function getSourcePort(edge: Record<string, any>): string {
  const port = edge.sourcePort ?? edge.properties?.outputPort ?? 'main';
  return port === 'out' ? 'main' : port;
}

export function getVisualSourcePort(
  edge: Record<string, any>,
  toolCode?: string,
) {
  return toolCode === 'quality-check' || toolCode === 'sub-pipeline'
    ? getSourcePort(edge)
    : 'out';
}

export function serializeEdgePort(
  visualPort?: string,
  properties: Record<string, any> = {},
) {
  return visualPort && visualPort !== 'out'
    ? visualPort
    : getSourcePort({ properties });
}

/** 加入 source→target 前，确认 target 没有通向 source 的路径。 */
export function createsCycle(
  edges: { sourceNodeId: string; targetNodeId: string }[],
  source: string,
  target: string,
) {
  const pending = [target];
  const visited = new Set<string>();
  while (pending.length > 0) {
    const current = pending.pop()!;
    if (current === source) return true;
    if (visited.has(current)) continue;
    visited.add(current);
    for (const edge of edges) {
      if (edge.sourceNodeId === current) pending.push(edge.targetNodeId);
    }
  }
  return false;
}

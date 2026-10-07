(function(root) {
  function operationalDay(value = new Date()) {
    const text = typeof value === 'string' ? value : '';
    if (/^\d{4}-\d{2}-\d{2}$/.test(text)) return text;
    if (!value) return '';
    const date = new Date(value);
    if (!Number.isFinite(date.getTime())) return '';
    const parts = new Intl.DateTimeFormat('pt-BR',{timeZone:'America/Sao_Paulo',year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(date);
    const get = name => parts.find(p => p.type === name)?.value || '';
    return get('year') + '-' + get('month') + '-' + get('day');
  }
  function connectorUnavailable(body) {
    const status = String(body?.status || '').toLowerCase();
    return ['faulted','unavailable','reserved','unknown'].includes(status) ||
      (body?.error_code && String(body.error_code).toLowerCase() !== 'noerror');
  }
  function sessionPaidAmount(session) {
    const value = Number(session?.amount_paid ?? session?.paid_amount ?? 0);
    return Number.isFinite(value) ? Math.max(0,value) : 0;
  }
  root.AndreaoUI = {operationalDay,connectorUnavailable,sessionPaidAmount};
})(globalThis);

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
  function manualChargeBudget(value,cashReceived=false) {
    const raw=String(value??'').trim();
    const amount=raw===''?null:Number(raw.replace(',','.'));
    if(amount!==null && (!Number.isFinite(amount)||amount<1||amount>100000||Math.abs(amount*100-Math.round(amount*100))>0.000001))
      throw new Error('Informe um limite válido, a partir de R$ 1,00, com até duas casas decimais.');
    if(cashReceived && amount===null)throw new Error('Informe o valor recebido em dinheiro.');
    return {charge_limit_amount:amount,cash_received:!!cashReceived};
  }
  root.AndreaoUI = {operationalDay,connectorUnavailable,sessionPaidAmount,manualChargeBudget};
})(globalThis);

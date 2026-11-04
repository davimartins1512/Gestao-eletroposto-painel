(function(root){
  let options,customerId,rows=[],selection=new Set(),preview=null,busy=false;
  const eligible=s=>s.session_status==='finished'&&!s.reconciliation_required&&
    !['paid','not_required'].includes(s.payment_status)&&Number(s.total_amount)>Number(s.amount_paid||0);
  const get=id=>document.getElementById(id);
  async function api(path,body){
    const token=await options.token();if(!token)throw new Error('Sua sessão expirou.');
    const response=await fetch(options.backend+'/api/admin/payments/bulk-'+path,{method:'POST',
      headers:{authorization:'Bearer '+token,'content-type':'application/json'},body:JSON.stringify(body)});
    const data=await response.json();if(!response.ok||!data.success)throw new Error(data.error||'Falha na quitação.');return data;
  }
  function sync(){
    const selected=rows.filter(s=>selection.has(s.id)),amount=selected.reduce((sum,s)=>sum+Math.round((Number(s.total_amount)-Number(s.amount_paid||0))*100),0)/100;
    if(get('bulkChargeSummary'))get('bulkChargeSummary').textContent=selected.length+' selecionadas · '+options.money(amount);
    if(get('bulkChargePay'))get('bulkChargePay').disabled=!selected.length;
    const available=rows.filter(eligible),all=get('bulkChargeAll');
    if(all){all.checked=!!available.length&&selection.size===available.length;all.indeterminate=selection.size>0&&selection.size<available.length;}
    document.querySelectorAll('[data-bulk-charge]').forEach(input=>{input.checked=selection.has(input.dataset.bulkCharge);});
  }
  function toggle(id,checked){if(rows.some(s=>s.id===id&&eligible(s))){checked?selection.add(id):selection.delete(id);}sync();}
  function toggleAll(checked){selection=new Set(checked?rows.filter(eligible).map(s=>s.id):[]);sync();}
  async function open(all=false){
    if(busy)return;
    if(all)toggleAll(true);if(!selection.size)return;
    busy=true;
    try{
      const ids=Array.from(selection),owner=customerId;
      const data=await api('preview',{customer_id:owner,session_ids:ids});
      if(data.blocked)throw new Error('Há valores em conferência nesta seleção. Regularize-os antes de quitar em lote.');
      preview={...data,customerId:owner,ids};
      options.modal('Quitar recargas selecionadas',`<p><b>${data.count} recargas · ${options.money(data.total)}</b></p>
        <p>Confirme o dinheiro que já recebeu por fora. As demais recargas do cliente continuarão pendentes.</p>
        ${data.grouped_count?'<div class="msg warning">Os links agrupados envolvidos serão encerrados no sistema. O histórico será preservado para conferir eventual pagamento posterior.</div>':''}
        <div class="form-grid"><div><label>Forma de pagamento</label><select id="bulkChargeMethod"><option value="pix_manual">Pix recebido por fora</option><option value="cash">Dinheiro</option><option value="card_manual">Cartão recebido por fora</option><option value="other">Outro</option></select></div>
        <div><label>Valor recebido</label><input type="number" min="${data.total}" step="0.01" id="bulkChargeAmount" value="${Number(data.total).toFixed(2)}"></div>
        <div class="full"><label>Observação</label><input id="bulkChargeNote" maxlength="2000"></div></div>
        <label class="bulk-confirm"><input type="checkbox" id="bulkChargeReceived"> Confirmo que já recebi este valor.</label>
        <p id="bulkChargeError" role="alert"></p><button type="button" class="btn" id="bulkChargeConfirm" onclick="AndreaoBulk.confirm()">Confirmar quitação de ${data.count} recargas</button>`);
    }catch(error){alert(error.message);}finally{busy=false;}
  }
  async function confirm(){
    if(busy||!preview)return;
    const button=get('bulkChargeConfirm'),error=get('bulkChargeError');
    if(!get('bulkChargeReceived').checked){error.textContent='Confirme o recebimento antes de dar baixa.';return;}
    const received=Number(get('bulkChargeAmount').value);
    if(!Number.isFinite(received)||received<Number(preview.total)){error.textContent='O valor recebido deve cobrir toda a seleção.';return;}
    busy=true;button.disabled=true;
    try{
      const result=await api('approve',{customer_id:preview.customerId,session_ids:preview.ids,fingerprint:preview.fingerprint,
        payment_method:get('bulkChargeMethod').value,received_amount:received,note:get('bulkChargeNote').value,confirm_received:true});
      const id=preview.customerId;preview=null;selection.clear();options.close();
      alert(result.already_paid?'As recargas já estavam quitadas.':result.sessions_paid+' recargas quitadas.'+(Number(result.change_due)>0?' Confira o troco de '+options.money(result.change_due)+'.':''));
      await options.refresh(id);
    }catch(err){error.textContent=err.message;}finally{busy=false;button.disabled=false;}
  }
  root.AndreaoBulk={init(o){options=o;},context(id,pending){customerId=id;rows=pending;selection.clear();preview=null;},eligible,toggle,toggleAll,open,confirm,sync};
})(globalThis);

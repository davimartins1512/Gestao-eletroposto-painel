/* ANDREAO Fleet: painel de gestao. Nenhuma escrita em comandos do veiculo ou camera. */
(() => {
  'use strict';
  const byId = id => document.getElementById(id);
  const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const brl = value => Number(value || 0).toLocaleString('pt-BR', {style:'currency', currency:'BRL'});
  const shortDate = value => value ? String(value).slice(0, 10).split('-').reverse().join('/') : '—';
  const number = (value, digits = 0) => value == null ? '—' : Number(value).toLocaleString('pt-BR', {maximumFractionDigits:digits});
  const plate = value => String(value || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
  const state = {user:null, organization:null, vehicles:[], entries:[], telemetry:[], drivers:[], settings:[], statuses:[], recordings:[], charges:[], controlsAvailable:false,selected:null, tab:'history', pending:false};
  const labels = {available:'Disponível', rented:'Em locação', maintenance:'Manutenção', inactive:'Inativo'};
  const types = {maintenance:'Manutenção', expense:'Despesa', charge:'Recarga', trip:'Trajeto', inspection:'Vistoria', incident:'Ocorrência', other:'Outro'};
  const features = [
    ['gps','GPS contínuo','Posição e atualização de localização'],
    ['trip_history','Histórico de trajetos','Rotas e períodos de deslocamento'],
    ['traction_battery','Bateria de tração (SOC/SOH)','Valores reais, somente de BMS ou fonte verificada'],
    ['battery_12v','Bateria auxiliar 12V','Monitoramento e proteção contra descarga'],
    ['charging_location','Local das recargas','Relaciona recarga confirmada à localização'],
    ['mobile_data','Uso de dados móveis','Consumo da rede e franquia quando operadora permitir'],
    ['record_when_on','Vídeo contínuo ao ligar','Gravação circular de 24h: exige hardware validado'],
    ['parked_recording','Gravação desligado / sentinela','Opcional: exige proteção comprovada da bateria 12V'],
    ['live_camera','Câmeras ao vivo','Visualizar sem interromper gravação local'],
    ['cabin_microphone','Áudio junto com vídeo','Opcional, com informação aos ocupantes; sem escuta secreta'],
    ['alerts','Alertas de falhas e ocorrências','Avisos quando dispositivo parar ou houver incidente']
  ];
  let db;

  function notify(message, isError = false) {
    const node = byId('status');
    node.textContent = message;
    node.classList.toggle('error', isError);
  }
  function activeVehicle() { return state.vehicles.find(v => v.id === state.selected) || null; }
  function latest(vehicleId) { return state.telemetry.find(t => t.vehicle_id === vehicleId) || null; }
  function lastReading(value, suffix = '') { return value == null ? '—' : number(value, 1) + suffix; }
  function pill(status) { return '<span class="pill '+(['maintenance','inactive'].includes(status)?'warn':status==='rented'?'neutral':'')+'">'+escapeHtml(labels[status] || status)+'</span>'; }
  function field(name, label, input, hint = '') {
    return '<div class="field"><label for="f_'+name+'">'+label+'</label>'+input+(hint?'<small>'+hint+'</small>':'')+'</div>';
  }
  function textInput(name, value = '', max = 100, required = false) {
    return '<input id="f_'+name+'" name="'+name+'" value="'+escapeHtml(value)+'" maxlength="'+max+'" '+(required?'required':'')+'>';
  }
  function numberInput(name, value, step = '1') {
    return '<input id="f_'+name+'" name="'+name+'" type="number" step="'+step+'" min="0" value="'+escapeHtml(value ?? '')+'">';
  }
  function selectInput(name, options, selected) {
    return '<select id="f_'+name+'" name="'+name+'">'+options.map(([v,label])=>'<option value="'+escapeHtml(v)+'" '+(v === selected?'selected':'')+'>'+escapeHtml(label)+'</option>').join('')+'</select>';
  }
  function openDialog(kind, record = null) {
    const vehicle = activeVehicle();
    if ((kind === 'entry' || kind === 'charge') && !vehicle) return;
    const form = byId('editorForm');
    form.dataset.kind = kind;
    form.dataset.recordId = record?.id || '';
    form.dataset.vehicleId = vehicle?.id || '';
    byId('editorError').textContent = '';
    byId('editorTitle').textContent = kind === 'vehicle' ? (record?'Editar veículo':'Adicionar veículo') : kind === 'charge' ? 'Registrar recarga · '+vehicle.plate : (record?'Editar registro':'Novo registro · '+vehicle.plate);
    if (kind === 'vehicle') {
      byId('editorFields').innerHTML =
        field('plate','Placa *',textInput('plate',record?.plate,7,true),'Somente letras e números, sem hífen.')+
        field('model','Modelo *',selectInput('model', [['BYD Dolphin Mini','BYD Dolphin Mini'],['BYD Yuan Plus','BYD Yuan Plus'],['BYD Dolphin Plus','BYD Dolphin Plus']],record?.model || 'BYD Dolphin Mini'))+
        field('nickname','Identificação',textInput('nickname',record?.nickname,100))+
        field('vehicle_year','Ano',numberInput('vehicle_year',record?.vehicle_year))+
        field('usage_type','Finalidade',selectInput('usage_type',[['rental','Locação'],['private','Particular']],record?.usage_type || 'rental'))+
        field('status','Situação',selectInput('status',Object.entries(labels),record?.status || 'available'))+
        field('vin','Chassi / VIN (opcional)',textInput('vin',record?.vin,40))+
        field('notes','Observações','<textarea id="f_notes" name="notes" maxlength="2000" rows="3">'+escapeHtml(record?.notes || '')+'</textarea>');
    } else if (kind === 'charge') {
      const today = new Date();
      const local = new Date(today.getTime() - today.getTimezoneOffset()*60000).toISOString().slice(0,16);
      byId('editorFields').innerHTML =
        '<p class="notice">Registro manual: a localização e a recarga permanecem NÃO VERIFICADAS até serem confirmadas por telemetria ou carregador.</p>'+
        field('start_at','Início da recarga *','<input id="f_start_at" name="start_at" type="datetime-local" required value="'+local+'">')+
        field('location_label','Local informado',textInput('location_label','',160))+
        field('latitude','Latitude (opcional)', '<input id="f_latitude" name="latitude" type="number" step="any" min="-90" max="90">')+
        field('longitude','Longitude (opcional)', '<input id="f_longitude" name="longitude" type="number" step="any" min="-180" max="180">')+
        field('soc_start_pct','SOC no início %', '<input id="f_soc_start_pct" name="soc_start_pct" type="number" step="0.1" min="0" max="100">')+
        field('soc_end_pct','SOC no fim %', '<input id="f_soc_end_pct" name="soc_end_pct" type="number" step="0.1" min="0" max="100">')+
        field('energy_kwh','Energia (kWh)',numberInput('energy_kwh',null,'0.001'))+
        field('cost_brl','Custo em reais',numberInput('cost_brl',null,'0.01'))+
        field('notes','Observações','<textarea id="f_notes" name="notes" maxlength="1000" rows="3"></textarea>');
    } else {
      byId('editorFields').innerHTML =
        field('entry_type','Tipo de registro *',selectInput('entry_type',Object.entries(types),record?.entry_type || 'maintenance'))+
        field('occurred_on','Data *','<input id="f_occurred_on" name="occurred_on" type="date" required value="'+escapeHtml(record?.occurred_on || new Date().toLocaleDateString('en-CA'))+'">')+
        field('description','Descrição *',textInput('description',record?.description,300,true))+
        field('amount_brl','Custo em reais',numberInput('amount_brl',record?.amount_brl ?? 0,'0.01'))+
        field('energy_kwh','Energia carregada (kWh)',numberInput('energy_kwh',record?.energy_kwh,'0.001'))+
        field('odometer_km','Odômetro (km)',numberInput('odometer_km',record?.odometer_km,'0.1'))+
        field('notes','Detalhes','<textarea id="f_notes" name="notes" maxlength="2000" rows="3">'+escapeHtml(record?.notes || '')+'</textarea>');
    }
    byId('editor').showModal();
    byId('editorFields').querySelector('input,select')?.focus();
  }
  function closeDialog() { byId('editor').close(); }

  async function saveForm(event) {
    event.preventDefault();
    if (state.pending) return;
    const form = byId('editorForm'), values = Object.fromEntries(new FormData(form)), kind = form.dataset.kind;
    const isVehicle = kind === 'vehicle', isCharge = kind === 'charge';
    const id = form.dataset.recordId;
    const amount = value => value === '' || value == null ? null : Number(value);
    const payload = isVehicle ? {
      plate:plate(values.plate),model:String(values.model || '').trim(),
      nickname:values.nickname?.trim() || null,
      vehicle_year:values.vehicle_year?Number(values.vehicle_year):null,
      usage_type:values.usage_type,status:values.status,
      vin:values.vin?.trim() || null,notes:values.notes?.trim() || null
    } : isCharge ? {
      start_at:values.start_at ? new Date(values.start_at).toISOString() : null,
      location_label:values.location_label?.trim()||null,
      latitude:amount(values.latitude),longitude:amount(values.longitude),
      soc_start_pct:amount(values.soc_start_pct),soc_end_pct:amount(values.soc_end_pct),
      energy_kwh:amount(values.energy_kwh),cost_brl:amount(values.cost_brl),
      notes:values.notes?.trim()||null, location_source:'manual', verification_status:'unverified'
    } : {
      entry_type:values.entry_type,occurred_on:values.occurred_on,description:values.description?.trim(),
      amount_brl:Number(values.amount_brl || 0),energy_kwh:values.energy_kwh===''?null:Number(values.energy_kwh),
      odometer_km:values.odometer_km===''?null:Number(values.odometer_km),notes:values.notes?.trim() || null
    };
    if (isVehicle && (!/^[A-Z0-9]{7}$/.test(payload.plate) || !payload.model)) return byId('editorError').textContent = 'Informe uma placa válida e o modelo.';
    if (isCharge && (!payload.start_at || (payload.latitude==null)!==(payload.longitude==null))) return byId('editorError').textContent = 'Informe data e ambas as coordenadas, ou deixe as duas vazias.';
    if (!isVehicle && !isCharge && (!payload.description || payload.description.length < 2 || !payload.occurred_on)) return byId('editorError').textContent = 'Informe data e descrição.';
    state.pending = true;byId('saveEditor').disabled = true;byId('editorError').textContent = '';
    try {
      const table = isVehicle ? 'fleet_vehicles' : isCharge ? 'fleet_charge_locations' : 'fleet_entries';
      if (!id) Object.assign(payload,isVehicle ?
        {user_id:state.user.id,organization_id:state.organization.id} :
        {user_id:state.user.id,vehicle_id:form.dataset.vehicleId});
      else payload.updated_at = new Date().toISOString();
      const query = id ? db.from(table).update(payload).eq('id',id).eq('user_id',state.user.id) :
        db.from(table).insert(payload);
      const {data,error} = await query.select('id').single();
      if (error) throw error;
      if (isVehicle) state.selected = data.id;
      closeDialog();
      await load();
      notify(isVehicle ? 'Veículo salvo com sucesso.' : isCharge ? 'Recarga manual registrada (não verificada).' : 'Registro salvo com sucesso.');
    } catch (error) {
      byId('editorError').textContent = error.code === '23505' ? 'Essa placa já está cadastrada nesta frota.' : (error.message || 'Falha ao salvar.');
    } finally {state.pending = false;byId('saveEditor').disabled = false;}
  }
  async function ensureOrganization() {
    const {data,error} = await db.from('fleet_organizations').select('*').eq('user_id',state.user.id).order('created_at',{ascending:true}).limit(1);
    if (error) throw error;
    if (data?.length) return data[0];
    const created = await db.from('fleet_organizations').insert({user_id:state.user.id,name:'ANDREAO ELETROPOSTO'}).select('*').single();
    if (created.error) throw created.error;
    return created.data;
  }
  async function load() {
    notify('Sincronizando frota...');
    state.organization = await ensureOrganization();
    const [vehicles,entries,telemetry,drivers,settings,statuses,recordings,charges] = await Promise.all([
      db.from('fleet_vehicles').select('*').eq('organization_id',state.organization.id).order('created_at',{ascending:true}),
      db.from('fleet_entries').select('*').eq('user_id',state.user.id).order('occurred_on',{ascending:false}).limit(500),
      db.from('fleet_telemetry').select('*').eq('user_id',state.user.id).order('recorded_at',{ascending:false}).limit(1000),
      db.from('drivers').select('plate,name,active').eq('user_id',state.user.id).limit(500),
      db.from('fleet_feature_settings').select('*').eq('user_id',state.user.id).limit(2000),
      db.from('fleet_feature_status').select('*').eq('user_id',state.user.id).limit(2000),
      db.from('fleet_recording_state').select('*').eq('user_id',state.user.id).limit(500),
      db.from('fleet_charge_locations').select('*').eq('user_id',state.user.id).order('start_at',{ascending:false}).limit(1000)
    ]);
    for (const result of [vehicles,entries,telemetry]) if (result.error) throw result.error;
    state.vehicles = vehicles.data || [];
    state.entries = entries.data || [];
    state.telemetry = telemetry.data || [];
    state.drivers = drivers.error ? [] : drivers.data || [];
    state.controlsAvailable = ![settings,statuses,recordings,charges].some(r=>r.error);
    state.settings = settings.error ? [] : settings.data || [];
    state.statuses = statuses.error ? [] : statuses.data || [];
    state.recordings = recordings.error ? [] : recordings.data || [];
    state.charges = charges.error ? [] : charges.data || [];
    if (!state.vehicles.some(v=>v.id===state.selected)) state.selected = state.vehicles[0]?.id || null;
    byId('content').hidden = false;
    render();
    notify(state.controlsAvailable ? 'Frota sincronizada. Ativações são configurações, não prova de hardware funcionando.' : 'Frota carregada; módulos de controle/recarga dependem da migração adicional ainda não aplicada.');
  }
  function render() {
    const all = state.vehicles, costs = state.entries.filter(e=>['maintenance','expense','charge'].includes(e.entry_type));
    byId('statTotal').textContent = all.length;
    byId('statRented').textContent = all.filter(v=>v.status==='rented').length;
    byId('statMaintenance').textContent = all.filter(v=>v.status==='maintenance').length;
    byId('statExpenses').textContent = brl(costs.reduce((sum,e)=>sum+Number(e.amount_brl||0),0));
    const query = byId('search').value.toLowerCase().trim();
    const filtered = all.filter(v=>([v.plate,v.nickname,v.model].join(' ').toLowerCase()).includes(query));
    byId('vehicles').innerHTML = filtered.length ? filtered.map(v=>
      '<button type="button" class="vehicle '+(v.id===state.selected?'active':'')+'" data-vehicle="'+escapeHtml(v.id)+'"><span><strong>'+escapeHtml(v.nickname||v.model)+'</strong><small>'+escapeHtml(v.plate)+' · '+escapeHtml(v.model)+'</small></span>'+pill(v.status)+'</button>').join('') :
      '<div class="empty">Nenhum veículo encontrado. Use “Adicionar veículo”.</div>';
    renderDetail();
  }
  function renderDetail() {
    const v = activeVehicle(), root = byId('vehicleDetail');
    if (!v) {root.innerHTML='<div class="empty"><h2>Comece cadastrando um veículo</h2><p>Os registros e as leituras aparecerão aqui.</p></div>';return;}
    const t = latest(v.id), recent = t && Date.now()-Date.parse(t.recorded_at)<120000;
    const compatibleDriver = state.drivers.find(d=>plate(d.plate)===v.plate);
    const title = '<div class="detail-head"><div><h2>'+escapeHtml(v.nickname||v.model)+'</h2><p>'+escapeHtml(v.plate)+' · '+escapeHtml(v.model)+(v.vehicle_year?' · '+v.vehicle_year:'')+'</p>'+pill(v.status)+'</div><button type="button" class="ghost" data-action="edit-vehicle">Editar</button></div>';
    const metrics = '<div class="meters"><div class="meter"><small>Carga atual (SOC)</small><strong>'+lastReading(t?.soc_pct,'%')+'</strong></div><div class="meter"><small>Saúde (SOH)</small><strong>'+lastReading(t?.soh_pct,'%')+'</strong></div><div class="meter"><small>Odômetro</small><strong>'+lastReading(t?.odometer_km,' km')+'</strong></div></div>';
    const origin = t ? '<div class="notice'+(recent?'':' warn')+'">Última leitura: '+new Date(t.recorded_at).toLocaleString('pt-BR')+' · Origem: '+escapeHtml(t.source)+'. '+(recent?'Dados recentes; a conexão contínua ainda deve ser validada.':'Leitura antiga, não é monitoramento ao vivo.')+'</div>' :
      '<div class="notice warn">Veículo cadastrado, mas ainda não existe integração de telemetria verificada. SOC, SOH, GPS e câmera não são dados disponíveis neste momento.</div>';
    const driver = compatibleDriver && v.usage_type==='rental' ? '<p><strong>Motorista cadastrado com a mesma placa:</strong> '+escapeHtml(compatibleDriver.name)+' <small>(confira a associação no painel de Motoristas)</small></p>' : '';
    const tabnames = [['history','Histórico'],['battery','Bateria e localização'],['charges','Recargas'],['camera','Câmeras e segurança'],['controls','Configurações']];
    const tabs = '<div class="tabs">'+tabnames.map(([key,label])=>'<button type="button" class="ghost '+(state.tab===key?'selected':'')+'" data-tab="'+key+'">'+label+'</button>').join('')+'</div>';
    let body = '';
    if (state.tab === 'history') {
      const entries = state.entries.filter(e=>e.vehicle_id===v.id);
      body = '<div class="section-actions"><h3>Operação e despesas</h3><button type="button" class="primary" data-action="add-entry">+ Registro</button></div>'+
        (entries.length?entries.map(e=>'<div class="row"><div><strong>'+escapeHtml(types[e.entry_type]||e.entry_type)+' · '+escapeHtml(e.description)+'</strong><small>'+shortDate(e.occurred_on)+(e.odometer_km!=null?' · '+number(e.odometer_km,1)+' km':'')+(e.energy_kwh!=null?' · '+number(e.energy_kwh,2)+' kWh':'')+'</small>'+(e.notes?'<small>'+escapeHtml(e.notes)+'</small>':'')+'</div><div class="right"><strong>'+brl(e.amount_brl)+'</strong><button class="ghost" type="button" data-edit-entry="'+escapeHtml(e.id)+'">Editar</button></div></div>').join(''):'<div class="empty">Sem registros. Cadastre manutenção, recargas, despesas, trajetos ou vistorias.</div>');
    } else if (state.tab === 'charges') {
      body = renderCharges(v);
    } else if (state.tab === 'controls') {
      body = renderControls(v);
    } else if (state.tab === 'battery') {
      body = '<div class="section-actions"><h3>Diagnóstico elétrico e GPS</h3></div>'+
        '<div class="row"><span>Autonomia</span><strong>'+lastReading(t?.range_km,' km')+'</strong></div>'+
        '<div class="row"><span>Temperatura da bateria</span><strong>'+lastReading(t?.battery_temp_c,' °C')+'</strong></div>'+
        '<div class="row"><span>Bateria auxiliar 12V</span><strong>'+lastReading(t?.voltage_12v,' V')+'</strong></div>'+
        '<div class="row"><span>Velocidade</span><strong>'+lastReading(t?.speed_kmh,' km/h')+'</strong></div>'+
        '<div class="row"><span>Carregando</span><strong>'+(t?.charging==null?'—':t.charging?'Sim':'Não')+'</strong></div>'+
        '<div class="row"><span>GPS</span><strong>'+(t?.latitude!=null&&t?.longitude!=null?'Coordenadas disponíveis (acesso restrito)':'Sem leitura')+'</strong></div>'+
        '<div class="notice">O SOH só será exibido quando fornecido por integração ou diagnóstico identificável. Não calculamos valores falsos com base na porcentagem de carga.</div>';
    } else {
      const recording=state.recordings.find(r=>r.vehicle_id===v.id);
      body = '<div class="section-actions"><h3>Câmeras originais e segurança</h3></div>'+
        '<div class="notice warn">Não há conexão autorizada com as câmeras deste veículo. A transmissão de estacionamento, gravações e modo sentinela exigem validação de compatibilidade e controles de privacidade. Nenhum vídeo é exibido ou gravado por este painel.</div>'+
        '<div class="row"><span>Transmissão ao vivo</span><strong>Não integrada</strong></div>'+ 
        '<div class="row"><span>Gravador local</span><strong>'+escapeHtml(recording?.recording_status||'Não verificado')+'</strong></div>'+ 
        '<div class="row"><span>Último segmento confirmado</span><strong>'+escapeHtml(recording?.last_segment_at?new Date(recording.last_segment_at).toLocaleString('pt-BR'):'Nenhum')+'</strong></div>'+
        '<div class="row"><span>Gravações e sentinela</span><strong>Não integradas</strong></div>'+
        '<div class="row"><span>Bloqueio / imobilização</span><strong>Não disponível</strong></div>';
    }
    root.innerHTML=title+metrics+origin+driver+tabs+body;
  }

  function effectiveStatus(code, vehicleId) {
    const status=state.statuses.find(s=>s.vehicle_id===vehicleId && s.feature_code===code);
    const display={unverified:'Não verificado',available:'Disponível',running:'Funcionando',degraded:'Degradado',blocked:'Bloqueado',offline:'Sem conexão',unsupported:'Incompatível'};
    return status ? display[status.effective_status]||'Não verificado' : 'Não verificado';
  }
  function renderControls(vehicle) {
    const warning='<div class="notice warn">Ativar uma função aqui apenas registra a preferência do gestor. <strong>Não faz o veículo gravar, transmitir vídeo ou ler o BMS</strong> até existir integração comprovada. O áudio não pode ser usado para escuta oculta: informe motoristas e ocupantes e valide as condições legais.</div>';
    if(!state.controlsAvailable)return '<h3>Controle por veículo</h3>'+warning+
      '<div class="notice warn">Extensão de banco de dados ainda não aplicada. Nenhum controle foi ativado. Requer migração e testes de segurança antes do uso real.</div>';
    return '<h3>Controle por veículo · '+escapeHtml(vehicle.plate)+'</h3>'+warning+
      '<div class="feature-grid">'+features.map(([code,label,description])=>{
        const row=state.settings.find(s=>s.vehicle_id===vehicle.id&&s.feature_code===code);
        const on=row?.desired_enabled===true;
        return '<label class="feature"><span class="feature-copy"><strong>'+escapeHtml(label)+'</strong><small>'+escapeHtml(description)+'</small><em>Condição técnica: '+escapeHtml(effectiveStatus(code,vehicle.id))+'</em></span>'+
          '<input type="checkbox" data-feature="'+escapeHtml(code)+'" '+(on?'checked':'')+' aria-label="Solicitar '+escapeHtml(label)+'"></label>';
      }).join('')+'</div>'+
      '<div class="notice">Gravação circular prevista em 24 horas; modo desligado permanece opcional e precisa da leitura real da bateria de 12V. O microfone só pode operar com comunicação perceptível aos ocupantes, habilitação explícita e registro dos acessos.</div>';
  }
  function renderCharges(vehicle) {
    if(!state.controlsAvailable)return '<h3>Local das recargas</h3><div class="notice warn">A extensão de banco para recargas ainda não foi aplicada. Nenhuma localização foi confirmada automaticamente.</div>';
    const recent=state.charges.filter(c=>c.vehicle_id===vehicle.id);
    return '<div class="section-actions"><h3>Recargas e locais</h3><button class="primary" type="button" data-action="add-charge">+ Recarga manual</button></div>'+
      '<div class="notice">Recargas automáticas só serão confirmadas após cruzamento validado de BMS, GPS e/ou sessão OCPP. Registros manuais não provam uma carga.</div>'+
      (recent.length?recent.map(c=>'<div class="row"><div><strong>'+escapeHtml(c.location_label||'Local não informado')+'</strong><small>'+escapeHtml(new Date(c.start_at).toLocaleString('pt-BR'))+' · '+escapeHtml(c.location_source)+' · '+escapeHtml(c.verification_status)+'</small><small>'+(c.energy_kwh!=null?number(c.energy_kwh,2)+' kWh · ':'')+(c.soc_start_pct!=null?number(c.soc_start_pct,1)+'% → ':'')+(c.soc_end_pct!=null?number(c.soc_end_pct,1)+'%':'')+'</small></div><strong>'+brl(c.cost_brl)+'</strong></div>').join('') :
       '<div class="empty">Nenhuma recarga registrada para este carro.</div>');
  }
  async function changeFeature(input){
    const v=activeVehicle(),code=input.dataset.feature;
    if(!v||!state.controlsAvailable||!features.some(f=>f[0]===code)){input.checked=!input.checked;return;}
    const enabled=input.checked;
    if(enabled&&code==='cabin_microphone'){
      const ok=window.confirm('ATENÇÃO: áudio contínuo na cabine envolve conversas de motoristas e passageiros. É necessário informá-los previamente, verificar a base legal e o aviso de captação. Esta ação apenas salva uma configuração; não liga nenhum microfone. Deseja registrar a preferência para avaliação?');
      if(!ok){input.checked=false;return;}
    }
    if(enabled&&code==='parked_recording'){
      const ok=window.confirm('O modo estacionado precisa de medição real e corte seguro da bateria auxiliar de 12V. Ainda NÃO está pronto para uso. Salvar apenas como preferência?');
      if(!ok){input.checked=false;return;}
    }
    input.disabled=true;
    try{
      const payload={vehicle_id:v.id,user_id:state.user.id,feature_code:code,desired_enabled:enabled,updated_at:new Date().toISOString()};
      const {error}=await db.from('fleet_feature_settings').upsert(payload,{onConflict:'vehicle_id,feature_code'});
      if(error)throw error;
      const ix=state.settings.findIndex(s=>s.vehicle_id===v.id&&s.feature_code===code);
      if(ix>=0)state.settings[ix]=payload;else state.settings.push(payload);
      notify('Preferência salva. Status real: '+effectiveStatus(code,v.id)+'.');
      renderDetail();
    }catch(e){input.checked=!enabled;notify('Falha ao salvar configuração: '+(e.message||'erro de acesso'),true);}
    finally{input.disabled=false;}
  }
  async function bootstrap() {
    const cfg=window.APP_CONFIG;
    if (!cfg?.supabaseUrl || !cfg?.supabaseKey || !window.supabase?.createClient) {
      notify('Configuração de acesso indisponível. Retorne ao painel administrativo.',true);return;
    }
    db=window.supabase.createClient(cfg.supabaseUrl,cfg.supabaseKey,{auth:{persistSession:true,autoRefreshToken:true}});
    const {data,error}=await db.auth.getUser();
    if (error || !data?.user) {
      notify('Acesso restrito. Entre primeiro no painel administrativo.',true);
      return;
    }
    state.user=data.user;
    try {await load();}
    catch (err) {
      const pending=['42P01','PGRST205','42501','PGRST204'].includes(err?.code);
      notify(pending?'Módulo preparado, mas o esquema do banco ou as permissões ainda não foram liberados. Nenhum dado foi alterado.':'Erro ao carregar a frota: '+String(err?.message||'Falha de conexão'),true);
    }
  }
  byId('refresh').addEventListener('click',()=>{void load().catch(err=>notify(err.message,true));});
  byId('newVehicle').addEventListener('click',()=>openDialog('vehicle'));
  byId('search').addEventListener('input',render);
  byId('closeEditor').addEventListener('click',closeDialog);
  byId('cancelEditor').addEventListener('click',closeDialog);
  byId('editorForm').addEventListener('submit',event=>{void saveForm(event);});
  document.addEventListener('click',event=>{
    const vehicleButton = event.target.closest('[data-vehicle]');
    if (vehicleButton) {state.selected=vehicleButton.dataset.vehicle;state.tab='history';render();return;}
    const tabButton = event.target.closest('[data-tab]');
    if (tabButton) {state.tab=tabButton.dataset.tab;renderDetail();return;}
    const action=event.target.closest('[data-action]')?.dataset.action;
    if (action==='edit-vehicle') openDialog('vehicle',activeVehicle());
    if (action==='add-entry') openDialog('entry');
    if (action==='add-charge') openDialog('charge');
    const edit=event.target.closest('[data-edit-entry]')?.dataset.editEntry;
    if (edit) {const entry=state.entries.find(e=>e.id===edit);if(entry)openDialog('entry',entry);}
  });
  document.addEventListener('change',event=>{
    const feature=event.target.closest('[data-feature]');
    if(feature)void changeFeature(feature);
  });
  void bootstrap();
})();

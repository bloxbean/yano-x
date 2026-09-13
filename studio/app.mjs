import {blueprintYaml,compatibleCapabilityOptions,decodeDeepLink,encodeDeepLink,
  importComponentCatalogSnapshot,normalizeIntent,projectBlueprintYaml} from './studio-core.mjs';

const form = document.querySelector('#intent-form');
const recipeSelect = document.querySelector('#recipe');
const releaseLabel = document.querySelector('#release');
const capabilityChooser = document.querySelector('#capability-chooser');
const answerFields = document.querySelector('#answer-fields');
let recipes=[]; let builtCapabilities=[]; let capabilities=[]; let builtArtifacts=[];
let chainDrafts=[]; let selectedChain=0;
let customCatalogs=[]; let release={recipes:[],yanoVersion:'unknown'}; let yaml='';

const load = path => fetch(path,{cache:'no-store'}).then(response => {
  if (!response.ok) throw new Error(`Could not load ${path}`); return response.json();
});

function readForm(forcedCapabilities) {
  const data=new FormData(form); const raw=Object.fromEntries(data.entries());
  raw.capabilities=forcedCapabilities || data.getAll('capability'); raw.answers={};
  raw.componentCatalogs=customCatalogs.map(value=>value.reference);
  answerFields.querySelectorAll('[data-answer]').forEach(input=>raw.answers[input.dataset.answer]=input.value);
  return raw;
}
function writeForm(values) {
  for (const [key,value] of Object.entries(values)) {
    if(['capabilities','answers','componentCatalogs'].includes(key)) continue;
    const field=form.elements.namedItem(key); if(field) field.value=Array.isArray(value)?value.join('\n'):value;
  }
}
function title(value) { return String(value).split(/[-:]/).map(part=>part[0]?.toUpperCase()+part.slice(1)).join(' '); }

function renderCapabilityChooser(intent) {
  const options=compatibleCapabilityOptions(
    recipes.find(recipe=>recipe.id===intent.recipe),capabilities,intent);
  const selected=new Set(intent.capabilities);
  const groups=new Map();
  for(const option of options) {
    const category=option.capability.category;
    if(!groups.has(category)) groups.set(category,[]);
    groups.get(category).push(option);
  }
  const fragments=[];
  for(const [category,items] of [...groups].sort(([a],[b])=>a.localeCompare(b))) {
    const group=document.createElement('fieldset'); group.className='capability-group';
    group.append(Object.assign(document.createElement('legend'),{textContent:title(category)}));
    for(const option of items) {
      const label=document.createElement('label'); label.className='capability-option';
      const checkbox=Object.assign(document.createElement('input'),{
        type:'checkbox',name:'capability',value:option.capability.id,
        checked:selected.has(option.capability.id),
        disabled:!option.compatible && !selected.has(option.capability.id)
      });
      const text=document.createElement('span');
      text.append(
        Object.assign(document.createElement('strong'),{textContent:option.capability.name}),
        Object.assign(document.createElement('small'),{textContent:
          `${option.capability.availability} · ${option.capability.maturity} · ${option.capability.scope || 'chain'}`})
      );
      if(option.reason) text.title=option.reason;
      label.append(checkbox,text); group.append(label);
    }
    fragments.push(group);
  }
  capabilityChooser.replaceChildren(...fragments);
}

function renderAnswers(required, values) {
  const current=values || {};
  answerFields.replaceChildren(...required.map(name=>{
    const label=document.createElement('label'); label.textContent=title(name);
    const input=Object.assign(document.createElement('input'),{
      value:current[name] || '',placeholder:`Non-secret ${name}`
    });
    input.dataset.answer=name; label.append(input); return label;
  }));
  answerFields.hidden=required.length===0;
}

function update(forcedCapabilities, forcedStateMachine) {
  const raw=readForm(forcedCapabilities);
  if(forcedStateMachine) raw.stateMachine=forcedStateMachine;
  let resolved=normalizeIntent(raw,recipes,release,capabilities); let intent=resolved.intent;
  renderCapabilityChooser(intent);
  renderAnswers(resolved.plan.requiredAnswers,intent.answers);
  resolved=normalizeIntent(readForm(),recipes,release,capabilities); intent=resolved.intent;
  const plan=resolved.plan;
  document.querySelector('#plan-title').textContent=resolved.recipe?.name || title(intent.recipe);
  document.querySelector('#recipe-note').textContent=resolved.recipe?.description || '';
  document.querySelector('#trust').textContent=resolved.recipe?.trustStatement || '';
  document.querySelector('#capabilities').replaceChildren(...plan.capabilityDetails.map(value=>{
    const chip=Object.assign(document.createElement('span'),{className:'chip',textContent:value.id});
    chip.title=`${value.availability} · ${value.maturity} · ${value.scope || 'chain'}`; return chip;
  }));
  document.querySelector('#artifacts').replaceChildren(...plan.artifacts.map(value=>
    Object.assign(document.createElement('li'),{textContent:value})));
  document.querySelector('#diagnostics').textContent=resolved.errors.join(' ');
  const status=document.querySelector('#validity'); status.textContent=resolved.errors.length?'Review':'Ready'; status.classList.toggle('error',Boolean(resolved.errors.length));
  chainDrafts[selectedChain]=intent;
  const shared=['network','members','runtime','deployment','name','memberKeys','nodeHosts','componentCatalogs'];
  chainDrafts=chainDrafts.map(chain=>({...chain,...Object.fromEntries(shared.map(key=>[key,intent[key]]))}));
  const projectErrors=chainDrafts.flatMap(chain=>normalizeIntent({...chain,
    memberKeys:chain.memberKeys.join(' '),nodeHosts:chain.nodeHosts.join(' ')},recipes,release,capabilities).errors
    .map(error=>`${chain.chainId}: ${error}`));
  try { yaml=projectErrors.length?'':projectBlueprintYaml(chainDrafts,release.yanoVersion); }
  catch(error) { projectErrors.push(error.message); yaml=''; }
  document.querySelector('#diagnostics').textContent=projectErrors.join(' ');
  status.textContent=projectErrors.length?'Review':'Configuration ready';
  status.classList.toggle('error',Boolean(projectErrors.length));
  renderChainList();
  document.querySelector('#preview').textContent=yaml || '# Resolve the items above to preview the blueprint.';
  document.querySelector('#download').disabled=!yaml;
  document.querySelector('#share').disabled=chainDrafts.length>1;
  const share=document.querySelector('#share');
  share.disabled=Boolean(projectErrors.length || customCatalogs.length || chainDrafts.length>1);
  share.title=customCatalogs.length?'Custom catalogs are local and deliberately excluded from links.':'';
  if (!projectErrors.length && !customCatalogs.length && chainDrafts.length===1)
    history.replaceState(null,'',encodeDeepLink(intent));
}

Promise.all([
  load('assets/appchain-recipe-catalog.json'),load('assets/appchain-capability-catalog.json'),
  load('assets/appchain-release-capability-index.json')
]).then(([recipeCatalog,capabilityCatalog,releaseIndex])=>{
  recipes=recipeCatalog.recipes; builtCapabilities=capabilityCatalog.capabilities;
  capabilities=[...builtCapabilities]; builtArtifacts=capabilityCatalog.artifacts; release=releaseIndex;
  recipeSelect.replaceChildren(...recipes.map(recipe=>Object.assign(document.createElement('option'),{value:recipe.id,textContent:recipe.name})));
  releaseLabel.textContent=`Yano X ${release.yanoVersion} · ${release.schemaStatus}`;
  const deepLink=decodeDeepLink(location.hash); writeForm(deepLink);
  update(deepLink.capabilities || [],deepLink.stateMachine);
}).catch(error=>{ document.querySelector('#diagnostics').textContent=error.message; });

function renderChainList() {
  const container=document.querySelector('#chain-list');
  container.replaceChildren(...chainDrafts.map((chain,index)=>{
    const button=document.createElement('button'); button.type='button'; button.className='secondary';
    button.textContent=`${index+1}. ${chain.chainId}`;
    button.setAttribute('aria-pressed',String(index===selectedChain));
    button.addEventListener('click',()=>{
      selectedChain=index; writeForm(chain);
      renderAnswers(normalizeIntent({...chain,memberKeys:chain.memberKeys.join(' '),
        nodeHosts:chain.nodeHosts.join(' ')},recipes,release,capabilities).plan.requiredAnswers,chain.answers);
      update(chain.capabilities,chain.answers?.stateMachine);
    }); return button;
  }));
  document.querySelector('#remove-chain').disabled=chainDrafts.length<=1;
  document.querySelector('#add-chain').disabled=chainDrafts.length>=32;
  document.querySelector('#chain-count').textContent=`${chainDrafts.length} chain${chainDrafts.length===1?'':'s'}`;
}
document.querySelector('#add-chain').addEventListener('click',()=>{
  if(chainDrafts.length>=32) return;
  update(); selectedChain=chainDrafts.length;
  let suffix=selectedChain+1;
  while(chainDrafts.some(chain=>chain.chainId===`chain-${suffix}`)) suffix++;
  writeForm({recipe:'audit-log',chainId:`chain-${suffix}`}); renderAnswers([],{}); update([]);
});
document.querySelector('#remove-chain').addEventListener('click',()=>{
  if(chainDrafts.length<=1) return;
  chainDrafts.splice(selectedChain,1); selectedChain=Math.min(selectedChain,chainDrafts.length-1);
  const chain=chainDrafts[selectedChain]; writeForm(chain);
  renderAnswers(Object.keys(chain.answers),chain.answers); update(chain.capabilities,chain.answers?.stateMachine);
});

document.querySelector('#catalog-import').addEventListener('click',async()=>{
  const file=document.querySelector('#catalog-file').files[0];
  const publicKey=document.querySelector('#publisher-key').value.trim();
  const status=document.querySelector('#catalog-status');
  if(!file) { status.textContent='Choose a catalog snapshot first.'; return; }
  try {
    const imported=await importComponentCatalogSnapshot(
      await file.text(),publicKey,builtCapabilities,builtArtifacts);
    if(customCatalogs.some(value=>value.catalog.catalogId===imported.catalog.catalogId))
      throw new Error('That component catalog is already imported.');
    if(customCatalogs.length>=16) throw new Error('Studio accepts at most 16 custom catalogs.');
    customCatalogs.push({...imported,fileName:file.name});
    capabilities=[...builtCapabilities,...customCatalogs.flatMap(value=>value.capabilities)];
    status.textContent=`Verified ${imported.catalog.catalogId}. Keep ${file.name} for the project.`;
    document.querySelector('#catalog-file').value='';
    document.querySelector('#publisher-key').value='';
    update();
  } catch(error) { status.textContent=error.message; }
});

form.addEventListener('input',()=>update());
window.addEventListener('hashchange',()=>{const values=decodeDeepLink(location.hash);writeForm(values);update(values.capabilities || [],values.stateMachine);});
document.querySelector('#download').addEventListener('click',()=>{
  const link=document.createElement('a'); link.href=URL.createObjectURL(new Blob([yaml],{type:'application/yaml'}));
  link.download='appchain.yaml'; link.click(); setTimeout(()=>URL.revokeObjectURL(link.href),0);
});
document.querySelector('#share').addEventListener('click',async event=>{
  const intent=normalizeIntent(readForm(),recipes,release,capabilities).intent;
  const url=`${location.origin}${location.pathname}${encodeDeepLink(intent)}`;
  await navigator.clipboard.writeText(url); event.currentTarget.textContent='Copied'; setTimeout(()=>event.currentTarget.textContent='Copy safe link',1200);
});

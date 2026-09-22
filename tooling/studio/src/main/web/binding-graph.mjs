/** Read-only graph model from versioned capability metadata; never executes or authorizes a binding. */
export function bindingGraph(manifest) {
  const entries=manifest?.crossCutting;
  if(!Array.isArray(entries)) throw new Error('Expected a capability manifest with crossCutting entries.');
  const matches=entries.filter(entry=>entry.capabilityId==='declarative-event-bindings');
  if(matches.length!==1) throw new Error('Expected exactly one declarative binding graph.');
  const attributes=matches[0].attributes;
  if(attributes?.schema!=='yano-x-binding-graph-v1') throw new Error('Unsupported binding graph schema.');
  const names=Object.keys(attributes);
  if(names.length>1+256*5) throw new Error('Binding graph exceeds its metadata limit.');
  const ids=[...new Set(names.filter(key=>key.startsWith('binding.')).map(key=>key.split('.')[1]))].sort();
  const bounded=value=>typeof value==='string' && value.length>0 && value.length<=256;
  const nodes=new Map();
  const edges=ids.map(id=>{
    if(!/^[a-z][a-z0-9-]{0,62}$/.test(id)) throw new Error('Invalid binding identifier.');
    const prefix=`binding.${id}.`;
    const source=attributes[`${prefix}source`], event=attributes[`${prefix}event`];
    const target=attributes[`${prefix}target`], kind=attributes[`${prefix}targetKind`];
    if(![source,event,target].every(bounded) || !['command','effect'].includes(kind))
      throw new Error(`Incomplete binding metadata: ${id}.`);
    if(kind==='command' && !bounded(attributes[`${prefix}command`]))
      throw new Error(`Missing command metadata: ${id}.`);
    const from=`component:${source}`, to=`${kind==='command'?'component':'effect'}:${target}`;
    nodes.set(from,{id:from,label:source,kind:'component',rank:0});
    if(!nodes.has(to)) nodes.set(to,{id:to,label:target,kind:kind==='command'?'component':'effect',rank:0});
    return {id,from,to,event,command:attributes[`${prefix}command`] || ''};
  });
  if([...nodes.values()].filter(node=>node.kind==='component').length>16)
    throw new Error('Too many component instances in binding graph.');
  // Longest-path layering is deterministic. Continued relaxation exposes cycles in untrusted snapshots.
  for(let pass=0;pass<nodes.size;pass++) {
    let changed=false;
    for(const edge of edges) {
      const from=nodes.get(edge.from), to=nodes.get(edge.to);
      if(to.rank<=from.rank) { to.rank=from.rank+1; changed=true; }
    }
    if(!changed) break;
    if(pass===nodes.size-1) throw new Error('Binding metadata contains a cycle.');
  }
  const rows=new Map();
  const ordered=[...nodes.values()].sort((a,b)=>a.rank-b.rank || a.id.localeCompare(b.id,'en'));
  for(const node of ordered) {
    node.row=rows.get(node.rank) || 0;
    rows.set(node.rank,node.row+1);
  }
  return {nodes:ordered,edges};
}

/** Renders with DOM text nodes only: imported names cannot inject HTML or SVG markup. */
export function renderBindingGraph(container, graph, document=container.ownerDocument) {
  const ns='http://www.w3.org/2000/svg';
  const element=(name,attributes={},text)=>{
    const node=document.createElementNS(ns,name);
    for(const [key,value] of Object.entries(attributes)) node.setAttribute(key,String(value));
    if(text!==undefined) node.textContent=text;
    return node;
  };
  const width=Math.max(500,...graph.nodes.map(node=>node.rank*280+240));
  const height=Math.max(160,...graph.nodes.map(node=>node.row*130+110));
  const svg=element('svg',{viewBox:`0 0 ${width} ${height}`,role:'img','aria-label':'Declarative event binding graph'});
  svg.append(element('title',{},'Declarative event bindings — imported metadata, not a verified chain identity'));
  const positions=new Map(graph.nodes.map(node=>[node.id,{x:20+node.rank*280,y:30+node.row*130}]));
  for(const edge of graph.edges) {
    const from=positions.get(edge.from), to=positions.get(edge.to);
    svg.append(element('path',{d:`M${from.x+190},${from.y+25} L${to.x-8},${to.y+25}`,
      class:'binding-edge'}));
    svg.append(element('path',{d:`M${to.x-16},${to.y+20} L${to.x-8},${to.y+25} L${to.x-16},${to.y+30}`,
      class:'binding-edge'}));
  }
  for(const node of graph.nodes) {
    const {x,y}=positions.get(node.id);
    const group=element('g');
    group.append(element('title',{},`${node.kind}: ${node.label}`));
    group.append(element('rect',{x,y,width:190,height:50,rx:8,class:`binding-node binding-${node.kind}`}));
    group.append(element('text',{x:x+10,y:y+30,class:'binding-label'},
      node.label.length>23?`${node.label.slice(0,22)}…`:node.label));
    svg.append(group);
  }
  const list=document.createElement('ul');
  for(const edge of graph.edges) {
    const item=document.createElement('li');
    item.textContent=`${edge.id}: ${edge.from.slice(10)} / ${edge.event} → ${edge.to}`
      +(edge.command?` / ${edge.command}`:'');
    list.append(item);
  }
  container.replaceChildren(svg,list);
}

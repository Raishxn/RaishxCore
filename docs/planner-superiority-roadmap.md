# Roadmap técnico — RaishxCore Planner além do Thunderbolt V2

## 1. Objetivo e regra de honestidade

O objetivo é construir um planner de autocrafting para AE2 que seja um
**superset semântico verificável** do Thunderbolt V2 e que, ao mesmo tempo,
tenha impacto menor e previsível no servidor, quantidades exatas, diagnóstico
melhor e integração sustentável com as versões suportadas do AE2.

Este documento não declara que o objetivo já foi atingido. A expressão
“melhor que o Thunderbolt V2” só poderá ser usada depois que todos os gates da
seção 15 passarem contra o mesmo commit fixado do Thunderbolt, no mesmo Java,
hardware, heap, warmup, dataset e política de timeout.

São proibidas comparações que:

- executem um planner pela API pública e o outro por um atalho de teste;
- reduzam a escala apenas para o RaishxCore;
- ignorem `DECLINE`, timeout, faltantes incorretos ou plano sem replay;
- comparem somente média, sem p50/p95/p99, memória e impacto no server thread;
- tratem fallback do AE2 como sucesso do RaishxCore;
- misturem resultados de algoritmos diferentes dentro de uma única tentativa;
- contem um plano rápido, mas semanticamente incorreto, como vitória.

## 2. Baseline confirmado em 2026-09-16

### 2.1 Pontos já fortes no RaishxCore

- quantidades `UfoAmount` baseadas em `BigInteger`, sem teto artificial de
  `long` no modelo matemático;
- planner iterativo, sem estouro de stack em cadeia com profundidade 20.000;
- batching exato, múltiplos outputs e subprodutos determinísticos: a ordem canônica de inputs faz
  uma entrada com rota selecionável ser resolvida antes de uma entrada que só existe como
  subproduto, então o coproduto é coletado do ramo irmão que o produz;
- replay de conservação em testes e benchmarks;
- snapshots imutáveis e cacheados por revisão da grid;
- deduplicação de requisições equivalentes em andamento;
- cancelamento por revisão, unload/alteração da grid, logout, desativação do
  planner e parada do servidor;
- captura de grafo cooperativa: fatias por grid, orçamento compartilhado por tick
  com rodízio entre grids, cache limitado por entradas **e** por bytes, cancelamento
  em todas as transições e fallback para o AE2 somente após descartar a tentativa
  inteira (`docs/planner-cooperative-capture.md`);
- workers, fila, timeout, operações, profundidade, snapshot e memória
  configuráveis;
- backpressure, limite de requisições simultâneas e circuit breaker por grid;
- fallback conservador: a captura AE2 recusa semântica que ainda não consegue
  representar, em vez de produzir plano sabidamente errado.

### 2.2 Limites atuais do RaishxCore

O modelo público `CraftingPattern` representa mapas exatos de inputs/outputs, um
subconjunto de outputs craftáveis, catalisadores presentes-e-devolvidos,
catalisadores que decaem por disparo, portadores de uso finito, slots fuzzy e
inputs emitidos por fonte autorizada. A ponte AE2 ainda recusa:

- padrões sem definição estável.

Outputs probabilísticos saíram da lista, mas apenas na leitura garantida: uma saída de
chance não é produção, não é demanda e não é rota, então o problema garantido é o que
não a contém, e é isso que a ponte resolve agora. O que continua fora é **explorar** a
rolagem como excedente esperado — oferecer a rota de chance como algo que rende na
média. Isso exigiria o motor carregar probabilidade, e não está feito. É a única família
que a referência também não responde.

O restante da lista anterior — fuzzy, remainder/container retornável, feedback
entre input e output incluindo catalisadores, emitters e inputs com mais de uma
opção válida — está implementado e preso pelo corpus diferencial. O feedback saiu
como esta seção previa, num adaptador separado (`FeedbackCyclePlanner`): o
componente é reconhecido, uma volta dele é precificada e o laço vira um
catalisador decaente que a maquinaria de balanço já entende, com o plano
reescrito de volta para os padrões reais, volta a volta. A ponte usa o adaptador
uma vez por requisição.

O algoritmo possui backtracking local limitado. Ciclos, ferramentas reutilizáveis
e catalisadores deixaram de ser o problema: o que continua fora é o **ótimo
global** de múltiplas rotas disputando estoque compartilhado, que é a Fase 4. O
adaptador de ciclos cobre o componente de rota única e recusa o resto; não é um
solver inteiro e não reivindica sê-lo.

A captura do grafo já não é monolítica: ela roda em fatias limitadas por grid
dentro de um orçamento compartilhado por tick, e uma captura que não termina é
retomada nos ticks seguintes (`docs/planner-cooperative-capture.md`). A fronteira
de uma fatia continua sendo **uma chave**, então uma chave com muitos padrões
ainda pode exceder a própria fatia; o estouro é debitado integralmente do
orçamento compartilhado, mas a meta de p95 de 2 ms por grid/tick ainda não foi
medida nem demonstrada por construção. Também não existem ainda histogramas por
fase, soak multi-grid nem teste de grid hostil.

O corpus diferencial de 2026-09-16 (`docs/planner-differential-corpus.md`)
encontrou e corrigiu uma falha concreta nessa fronteira: um subproduto exigido
era resolvido antes da rota irmã que o produz, então um composto cujo coproduto
ordena antes das chaves primárias recebia um faltante impossível. A ordem
canônica de inputs passou a resolver entradas com rota antes de entradas que só
existem como subproduto, e os quatro casos afetados saíram de `FALSE_NEGATIVE`
para `SUPPORTED` com `missingOverhead` 1,000.

Um subproduto continua não sendo rota selecionável, seguindo `Ae2PlanningSnapshot`,
que captura `craftableOutputs = Set.of(primary)`: nada que pergunte o que uma
receita fabrica passa a enxergá-lo como rota. Mas ele agora é **planejável e concorrente**: disparar
a receita pelo primário é o que o produz, e a rota secundária compete com as que declaram a chave —
a comparação já precifica os disparos extras e o primário que sai junto, então vence a mais barata,
seja ela qual for. Antes ela só era consultada quando nada declarava a chave, e uma receita que rendia
nove unidades por disparo perdia para qualquer receita que nomeasse a chave, por mais cara que fosse. `byproduct/surplus-secondary-demand` fixa a diferença —
quatro de um secundário contra um do primário obriga a disparar o produtor três vezes
mais. Antes o motor reportava os três secundários como faltantes, nomeando uma chave
que ninguém pode comprar; agora reporta o insumo do primário, que é o que de fato
precisa ser suprido. O papel explícito de saída (R2.3) segue valendo para o que falta:
distinguir as duas coisas na captura, em vez de derivá-las de `craftableOutputs`.

### 2.3 Baseline do Thunderbolt V2 a superar

O corpus de referência do Thunderbolt declara 11 famílias de grafo e 33 casos
de materiais (`MISSING`, `MINIMUM`, `UNBOUNDED`). A baseline atual exige
`SUPPORTED` em todos eles. Entre as capacidades cobertas estão:

- DAG de rota única, inclusive Fibonacci profundo;
- DAG com múltiplas rotas e armadilhas para escolha gulosa;
- ciclos de conversão e corte seguro de ciclo autoexpansivo;
- catalisador retornado diretamente;
- feedback conservativo e feedback com perda/seed inicial;
- ferramentas com número finito de usos;
- ingrediente fuzzy roteado para variante real reutilizável;
- replay de viabilidade e validação da lista de faltantes;
- backend V2 e candidato CP-SAT independentes;
- isolamento, timeout e quarentena de candidato não cooperativo;
- seleção determinística entre múltiplos engines e fallback integral.

O Thunderbolt já está à frente do RaishxCore em cobertura semântica. O
RaishxCore só poderá alegar superioridade depois de fechar essa diferença e
vencer nos gates adicionais abaixo.

### 2.4 AE2-VM como terceira referência especializada

Repositório: `https://github.com/TaoLe-si/AE2-VM`. Baseline inspecionada em
2026-09-16: commit `b03afe75f2f31ff01065ba574a80bb7220a8a237`
(`v1.10.7`). O AE2-VM passa a integrar a comparação ao lado do Thunderbolt V2
e do AE2 original, mas com um papel diferente: é a referência de arquitetura
especializada em cadeias profundas e pedidos gigantes.

Ideias relevantes demonstradas pelo projeto:

- compilação antecipada de patterns para bytecode;
- VM iterativa de pilha, sem recursão Java;
- agregação de demanda em DAG compartilhado em vez de expansão por caminho;
- cache/JIT de bundles de subárvore entre requisições;
- `BigInteger` interno e fast paths para valores comuns/potências de dois;
- suporte a catalisadores, feedback, durabilidade, fuzzy, conversões e padrões
  autorreferentes.

O relatório publicado pelo projeto declara 38/39 resultados corretos no corpus
expandido derivado do Thunderbolt. A limitação conhecida é um
`FALSE_POSITIVE` em `multi-dag/fibonacci/minimum`: a seleção heurística de rota
não encontra a combinação global correta. Esse caso é obrigatório no corpus do
RaishxCore e deve ser resolvido pelo solver global, nunca mascarado por fallback.

Riscos operacionais observados na baseline inspecionada:

- `CompletableFuture.supplyAsync()` sem executor/fila próprios;
- ausência de timeout e checkpoints cooperativos no cálculo principal;
- uma VM sincronizada serializa requisições concorrentes da mesma grid;
- cache estático por `IGrid`, sem limpeza de lifecycle evidente;
- fallback espera `nativeFuture.get()` dentro do worker;
- stack/profundidade possuem limites fixos;
- mixins obrigatórios e range AE2 mais amplo que a versão demonstrada;
- conversão final satura valores acima de `Long.MAX_VALUE` na fronteira AE2;
- ausência de CI e GameTests de lifecycle/múltiplas grids no checkout;
- reprodução local não foi imediata: `gradle-wrapper.jar` ausente, Java home de
  Windows fixado e plugin NeoForge não resolvido após os contornos locais.

Esses pontos são constatações da baseline fixada, não acusações sobre versões
futuras. A comparação sempre deve registrar o commit exato e reavaliar o estado
do projeto antes de publicar resultados.

O RaishxCore pode adotar como conceitos independentes — sem copiar código — a
representação intermediária compilada, agregação de DAG, memoização por revisão
e fast paths algébricos. Deve combiná-los com solver global, replay obrigatório,
captura incremental, executor limitado, lifecycle, backpressure e zero
tolerância a falso positivo.

## 3. Definição objetiva de “muito melhor”

Haverá três níveis de maturidade:

### Nível A — paridade semântica

- todos os 33 casos de referência do Thunderbolt retornam `SUPPORTED` pelo
  caminho de produção do RaishxCore;
- todo caso correto do corpus publicado pelo AE2-VM permanece correto e seu
  falso positivo multi-rota conhecido é resolvido;
- nenhum plano falha no replay de conservação;
- nenhuma tentativa trava ou ignora cancelamento;
- resultados são determinísticos entre ordens de registro e seeds de hash;
- integração real com AE2 preserva a semântica representada no corpus puro.

### Nível B — superioridade candidata

Além do Nível A:

- todos os casos adicionais do corpus Raishx passam;
- faltantes são mínimos nos casos canônicos onde o Thunderbolt admite conjunto
  válido, porém não mínimo;
- quantidades acima de `Long.MAX_VALUE` funcionam do request ao plano puro;
- captura é incremental/cooperativa e nunca monopoliza um tick;
- grids lentas, hostis ou enormes não degradam grids saudáveis;
- diagnóstico explica rota, corte, faltante, recusa e orçamento consumido;
- matriz de compatibilidade passa na menor e maior versão suportada do AE2.

### Nível C — superioridade comprovada

Além do Nível B, no corpus diferencial congelado:

- zero falso positivo e zero perda/dupe em unit tests e GameTests;
- 100% das capacidades obrigatórias suportadas; capacidades opcionais têm
  `DECLINE` explícito e testado, nunca erro silencioso;
- p95 do tempo no server thread respeita o orçamento de 2 ms por tick por grid
  na configuração de referência;
- p99 não apresenta cauda descontrolada sob múltiplas grids;
- mediana geométrica do tempo total é no máximo 75% da melhor baseline aplicável
  entre Thunderbolt V2 e AE2-VM, ou o RaishxCore vence pelo menos 80% dos
  cenários sem perder mais de 10% nos restantes;
- bytes alocados/operação são no máximo 80% da melhor baseline aplicável no agregado, salvo
  cenários `BigInteger` acima de `long`, que são relatados separadamente;
- qualidade de faltantes nunca é pior e é estritamente melhor em pelo menos um
  caso conhecido;
- relatório reproduzível contém versões, commit hashes, ambiente, resultados
  brutos e justificativa de qualquer exclusão.

Os números de 75%, 80%, 10%, 80% e 2 ms são gates iniciais. Podem ser alterados
antes do congelamento do corpus, com justificativa registrada; não podem ser
afrouxados depois de ver um resultado desfavorável sem invalidar a comparação.

## 4. Modelo semântico alvo

O modelo simples de mapas deve evoluir para tipos explícitos. Nenhuma semântica
especial será inferida apenas porque o mesmo recurso aparece em input/output.

### 4.1 Identidade de recurso

`PlanningKey` deve preservar:

- tipo AE2: item, fluido e futuros tipos registrados;
- ID de registro;
- componentes/data components relevantes;
- modo de comparação exato ou política de equivalência declarada;
- serialização canônica, estável e limitada em tamanho;
- conversão de bytes/unidades necessária pelo AE2.

Chaves nativas não podem ser acessadas pelo worker. O snapshot publica somente
valores imutáveis e adapters necessários para reconstruir o plano no thread
apropriado.

### 4.2 Inputs

Cada `PatternInput` deve declarar uma das formas:

- `Exact`: uma chave e quantidade exata consumida;
- `Alternatives`: conjunto ordenado de opções válidas;
- `Fuzzy`: chave lógica + política de matching + variantes capturadas;
- `Reusable`: catalisador retornado sem desgaste;
- `FiniteUse`: ferramenta/carrier com usos restantes e transformação após uso;
- `Remainder`: recipiente ou recurso retornado por execução;
- `External`: dependência satisfeita por emitter ou fonte virtual autorizada.

Também deve carregar multiplicador, política de validação do AE2 e identidade
do host/estoque quando duas opções não podem usar o mesmo item físico ao mesmo
tempo.

### 4.3 Outputs

Cada `PatternOutput` deve declarar:

- chave e quantidade exata;
- papel: primário craftável, coproduto craftável, subproduto ou remainder;
- garantia: determinístico ou probabilístico;
- para probabilístico: distribuição e política de planejamento. O planner não
  pode prometer ao crafting CPU um roll aleatório como saída garantida;
- possibilidade de realimentar outras rotas no mesmo plano;
- regra de arredondamento por execução, nunca após agregação indevida.

### 4.4 Padrão compilado

`CompiledPattern` deve conter:

- ID canônico e handle de reconstrução separado;
- prioridade AE2/provider e tie-break estável;
- inputs e outputs normalizados;
- outputs elegíveis como rota;
- custo estático e estimativas de expansão;
- flags de capacidade necessárias;
- origem/provider para diagnóstico;
- fingerprint de semântica usado na revisão/cache.

### 4.5 Estoque

O snapshot deve separar:

- estoque consumível normal;
- estoque reutilizável por host/rota;
- usos de durabilidade disponíveis;
- fontes emitíveis;
- recursos já reservados por jobs concorrentes;
- recursos virtuais/infinite explicitamente autorizados;
- disponibilidade exata no início da tentativa.

Nunca contar o mesmo recurso simultaneamente como consumível e reutilizável.

## 5. Pipeline planejado

### Fase 1 — captura incremental

Estado em 2026-09-17: os seis passos abaixo estão implementados para uma captura
por chave (`docs/planner-cooperative-capture.md`); falta refinar a fatia para dentro
de uma chave e medir a fatia.

1. [x] Registrar a revisão inicial da grid e do catálogo de padrões.
2. [x] Percorrer chaves/padrões em fatias com orçamento em nanos e número de arestas.
3. [x] Reagendar continuação para o tick seguinte quando o orçamento terminar: o
   chamador recebe um `Future` adiado e o tick seguinte retoma a captura.
4. [x] Cancelar se revisão, grid, jogador ou lifecycle mudar, inclusive quando a
   revisão muda enquanto a captura está aberta e sem nova requisição.
5. [x] Publicar o snapshot somente quando estiver completo e consistente.
6. [x] Nunca inserir snapshot parcial no cache: um cache byte-limitado só recebe
   snapshots concluídos.

Alvo padrão: até 2 ms por grid/tick, com orçamento global adicional para impedir
que muitas grids consumam 2 ms cada no mesmo tick. A fatia default é 2 ms/512
arestas por grid e o orçamento global é 4 ms por tick. O rodízio existe entre as
capturas de **uma mesma grid**; entre grids o pump percorre as bridges ativas sem
rotação e para quando o orçamento acaba, então uma grid pode esperar vários ticks
- mas termina, porque a captura é finita (`docs/planner-cooperative-capture.md`).
O alvo de p95 medido continua pendente: uma fatia só termina entre duas chaves.

Uma alternativa futura é manter snapshot imutável por eventos de mutação. Ela
só substitui a captura incremental depois que testes provarem que nenhum evento
do AE2 deixa o índice obsoleto.

### Fase 2 — normalização

- agregar duplicatas sem perder papéis semânticos;
- transformar remainders em arestas retornáveis explícitas;
- construir índices output→rotas e input→consumidores;
- calcular SCCs sobre outputs principais e laterais;
- classificar componentes como DAG, ciclo de conversão, feedback conservativo,
  feedback com perda, ciclo positivo ou ciclo desconhecido;
- identificar conflitos de alternativas, fuzzy, durabilidade e estoque host;
- produzir motivos estruturados de `DECLINE` para semântica não provada.

**Estado:** a parte de ciclos chegou, com escopo estreito. `FeedbackCyclePlanner`
caminha da receita do alvo pela rota única de cada insumo até um padrão repetir,
classifica a volta (conservativa, com perda ou positiva pelo sinal do saldo
líquido), recusa qualquer coisa com escolha, bifurcação, ganho próprio ou volta
mais rasa que o próprio decaimento, e recusa o componente de um padrão só porque
o crescimento auto-alimentado já é nativo do planner. Não há SCC geral: o que não
for uma volta simples de rota única continua caindo no `DECLINE` do planner base.

### Fase 3 — solução rápida para regiões simples

Usar o planner iterativo atual, depois de generalizado, para regiões que forem
provadamente DAG e sem conflito global. Manter:

- `BigInteger` ponta a ponta;
- batching por teto de divisão;
- consumo prévio de estoque;
- reaproveitamento determinístico de coprodutos;
- ordem estável de rotas;
- backtracking local com orçamento explícito.

O fast path nunca deve aceitar uma região só porque “parece simples”; a
classificação deve provar que não há conflito global relevante.

### Fase 4 — solver global limitado

Regiões com múltiplas rotas compartilhando estoque exigem solução inteira
global. Avaliar dois backends atrás do mesmo contrato:

- solver inteiro próprio, determinístico e limitado para componentes pequenos;
- backend CP-SAT opcional, se distribuição, plataforma e custo operacional
  forem aceitáveis.

Variáveis representam execuções inteiras de padrões. Restrições cobrem balanço
de cada recurso, estoque, target, seed reutilizável, usos finitos e limites de
rota. Ordem lexicográfica de objetivos:

1. obter plano viável;
2. minimizar faltantes ponderados;
3. minimizar número total de execuções;
4. minimizar sobreprodução determinística;
5. respeitar prioridade dos providers;
6. tie-break pelo ID canônico.

Timeout do solver deve retornar o melhor plano **já validado** ou `DECLINE`;
nunca um vetor parcial. Não misturar metade do fast path com metade de outro
backend sem replay global final.

Um objetivo da lista já vale fora do solver: faltantes **ponderados**. A API aceita pesos inteiros por
chave (`PlanningRequest.missingWeights`) e o planner ordena rotas por faltante ponderado em vez de
contagem de unidades. Peso um não é armazenado, então um request sem pesos percorre exatamente o
caminho de antes — a alocação no caso de conflito do benchmark é byte a byte a mesma. O caso
`multi-dag/weighted-shortage` fixa isso: duas rotas idênticas em unidades, separadas só pelo peso,
e o relatório passou de nomear o material 100 vezes mais caro para nomear o barato.

O caso mais afiado é `multi-dag/weighted-leaf-cost`, porque a comparação de rotas roda **antes** da
comparação de faltante — a demanda de folha, calculada de baixo para cima, ainda era contada em
unidades cruas. Dez unidades baratas perdiam para uma cara, a decisão nunca chegava ao comparador
ponderado, e o relatório nomeava o material caro com overhead `10.0`. Agora a folha custa o peso que
o request declarou, e toda a passada é feita na mesma moeda do faltante que ela tenta evitar.

**Estado em 2026-09-17: avaliado e não construído.** O que existe é o adaptador de ciclos da Fase 2,
que resolve a aritmética de um componente mas não otimiza entre rotas, e a busca gulosa do planner
iterativo com journal reversível. Nenhum dos dois reivindica o ótimo global, e o doc de classe
continua dizendo isso.

O que a avaliação encontrou é que a heurística é mais forte do que esta fase supunha, e que o
instrumento de falsificação é o corpus. A cadeia de comparação consulta, em ordem: prioridade do
provider, existência de déficit, demanda de folha **ponderada**, rank de alcançabilidade, faltante
**ponderado**, custo de entrada, número de execuções, rendimento e identificador. O déficit é
recalculado a cada consumo e a demanda de folha precifica o resto da árvore, então as decisões de
chaves diferentes se adaptam umas às outras em vez de serem escolhidas isoladamente.

Seis tentativas de construir um caso em que essa ordem perde para o ótimo global falharam, em
rodadas diferentes: duas rotas com o mesmo custo e execuções, um recurso escasso servindo duas
pontas, uma rota barata por unidade mas cara no conjunto, um subproduto abundante contra uma rota
declarada, e duas demandas disputando dois materiais escassos. Todas as formas que consegui montar
foram vencidas pela heurística, e a última está no corpus como
`multi-dag/shared-stock-conflict`, arranjada para que nenhuma combinação seja viável e o mínimo seja
1 unidade por uma escolha mista — ela reporta exatamente isso.

Então a conclusão é **não construir ainda**, e a condição que mudaria isso é explícita: um caso no
corpus que reporte um faltante ponderado acima do mínimo e cujo ótimo exija uma escolha conjunta. O
corpus é o instrumento; enquanto ele não falsificar, um solver inteiro é complexidade especulativa
num caminho que hoje acerta 27 de 27 casos de falta. O que a fase continua devendo não é o solver, é
a **prova** de que o ótimo vale para além dos casos tentados, e essa prova só aparece quando um caso
falha.

Um objetivo da lista já é respeitado, o de sobreprodução: entre rotas de mesmo custo e mesmo número
de execuções, quem decide agora é a que sobra menos, e não o identificador. A chave usada é
`(runs, rendimento)`, que ordena por sobra exatamente porque a demanda é a mesma para todos os
candidatos daquela chamada, e ambas já estavam em mãos — a primeira tentativa guardou a sobra num
campo novo e o gate de alocação pegou 21 % de regressão em `sibling_conflict`.

### Fase 5 — ciclos e feedback

- detectar SCCs considerando todos os outputs determinísticos;
- cortar ciclos puramente recursivos sem seed e explicar o corte;
- aceitar conversões reversíveis somente com orientação que preserve
  viabilidade e não crie material;
- provar feedback conservativo por estado interno;
- para feedback com perda, separar consumo líquido da reserva mínima inicial;
- recusar ciclos de ganho positivo no planner normal, salvo engine futuro
  explicitamente autorizado para essa semântica;
- proibir execuções negativas, fracionárias ou cancelamentos algébricos que não
  correspondam a uma ordem executável;
- construir uma agenda válida e replayar o seed passo a passo, não somente
  validar o balanço final.

### Fase 6 — fuzzy, alternatives e reutilizáveis

- capturar as opções que o próprio `IPatternDetails.IInput` aceita;
- preservar matching de componentes e política fuzzy do AE2;
- alocar variantes físicas globalmente para impedir double-spend;
- manter chave de host/rota para estoque reutilizável;
- distinguir catalisador infinito, ferramenta retornada e ferramenta que perde
  durabilidade;
- modelar cadeia de dano e quantidade de usos por item;
- validar remainder real com `getRemainingKey`;
- retornar lista explicável de qual variante física foi escolhida.

### Fase 7 — emitters e fontes externas

- definir adapter explícito para `canEmitFor`;
- capturar disponibilidade, limites e identidade da fonte sem levar live grid
  ao worker;
- distinguir emitter infinito de fornecedor limitado;
- evitar materializar quantidades gigantes desnecessariamente;
- marcar no plano o que será emitido e o que será extraído;
- recusar fonte cuja estabilidade não possa ser garantida durante a execução.

## 6. Planejamento versus execução

Um plano matematicamente balanceado não basta. Antes da conversão para AE2:

- replayar a agenda na ordem real de execução;
- provar que cada passo possui inputs/seed naquele instante;
- provar que nenhum estoque físico foi alocado duas vezes;
- separar outputs prometidos de bônus probabilísticos;
- validar que quantidades cabem nas fronteiras `long`/AE2 apenas no adapter;
- retornar `DECLINE` quando a execução AE2 não puder representar o plano exato;
- nunca truncar ou saturar `BigInteger` silenciosamente;
- registrar checksum/fingerprint do snapshot usado no plano.

Se a revisão mudar antes do commit, descartar o plano e reiniciar com snapshot
novo. A fase futura de execução transacional deve usar
`simulate -> reserve -> validate -> commit`, idempotency key e refund.

## 7. Resultado e taxonomia de falhas

Substituir o status pequeno atual por categorias que não escondam riscos:

- `SUPPORTED_COMPLETE`;
- `SUPPORTED_MISSING`;
- `DECLINED_CAPABILITY`;
- `DECLINED_BUDGET`;
- `CANCELLED_EXTERNAL`;
- `CANCELLED_REVISION`;
- `TIMED_OUT_COOPERATIVE`;
- `TIMED_OUT_QUARANTINED`;
- `INVALID_SNAPSHOT`;
- `INVALID_PLAN_REPLAY`;
- `ENGINE_ERROR`;
- `AE2_ADAPTER_UNREPRESENTABLE`.

Estado em 2026-09-17: essa taxonomia substitui o status pequeno atual apenas no
R2.3+. Até lá, a ponte publica o que já consegue distinguir: `capture deferred`
(a captura segue em ticks seguintes), `ae2 fallback: <motivo>` (a tentativa
Core foi descartada por inteiro e o AE2 planejou a requisição), `ae2: <motivo>`
(a ponte recusou antes de qualquer tentativa) e os status do planner iterativo
(`COMPLETE`, `MISSING_INGREDIENTS`, `CANCELLED`, timeout).

Falso positivo é defeito crítico: o engine aceitou e entregou plano inviável,
faltantes errados ou conservação inválida. Falso negativo é defeito distinto:
o engine recusou um caso que o modo diagnóstico consegue provar suportado.

Cada falha deve carregar:

- fase;
- capability requerida;
- grid/revisão sem expor dados sensíveis;
- budgets consumidos;
- padrão/chave responsável, quando seguro;
- indicação de fallback e engine seguinte;
- texto localizável para UI/comando.

## 8. Multi-engine e fallback

Criar um contrato público neutro semelhante a uma cadeia de candidatos, sem
copiar implementação do Thunderbolt:

- `check` barato e limitado;
- `capture` imutável e limitado no thread correto;
- `createSession` isolado;
- tentativa completa pertencente a um único engine;
- checkpoint cooperativo e deadline comum;
- fechamento obrigatório da sessão;
- fallback somente depois de descartar integralmente a tentativa anterior;
- cancelamento externo encerra a cadeia, não tenta outro engine;
- candidato não cooperativo entra em quarentena por grid/engine;
- AE2 vanilla permanece fallback final configurável.

O RaishxCore fast path, solver global e um possível CP-SAT devem aparecer como
candidatos independentes quando suas semânticas/diagnósticos diferirem. Não
atribuir ao fast path um plano finalizado por outro solver.

## 9. Determinismo

O mesmo snapshot e request devem gerar o mesmo resultado independente de:

- ordem de iteração de `HashMap`/`HashSet`;
- ordem de registro de providers equivalentes;
- quantidade de workers;
- timing de outras grids;
- reinício da JVM;
- seed aleatório do teste.

Testar o mesmo corpus com ordens permutadas e chaves de hash opacas. O plano
canônico deve comparar execuções, alocações, faltantes, agenda e motivo de
recusa; tempo e contadores não entram na igualdade semântica.

## 10. Corpus diferencial obrigatório

### 10.1 Importar como especificação comportamental

Recriar no RaishxCore, sem depender das classes de produção do Thunderbolt, os
11 grupos/33 modos da suíte de referência:

- single DAG disperso;
- single DAG Fibonacci profundo;
- multi-DAG com armadilha gulosa;
- multi-DAG Fibonacci;
- anel de conversão;
- ciclo autoexpansivo cortado com segurança;
- catalisador retornado;
- feedback bruto conservativo;
- feedback com perda e seed;
- durabilidade finita;
- variante fuzzy reutilizável.

Cada cenário roda em `MISSING`, `MINIMUM` e `UNBOUNDED`, salvo exceções
formalmente documentadas. A execução deve passar pela API de produção dos dois
planners.

Estado em 2026-09-16: a especificação independente existe em
`src/test/java/com/raishxn/ufocore/api/crafting/planner/differential` e cobre 17
grupos × 3 modos. As três primeiras capacidades de 10.1 (DAG disperso, DAG
Fibonacci profundo, multi-DAG com armadilha gulosa) retornam `SUPPORTED` pelo
caminho de produção do RaishxCore; catalisador, durabilidade, fuzzy, ciclos e
feedback são recusados na admissão, antes de planejar. O adapter do Thunderbolt e
o ambiente congelado ainda não existem. Desvios de escala adotados neste recorte
(8 conflitos na armadilha gulosa, cadeia de 20 000 e uma testemunha mínima na
Fibonacci multi-rota) estão documentados no mesmo arquivo e não podem ser
apresentados como resultado da suíte de referência.

### 10.2 Corpus adicional Raishx

Adicionar pelo menos:

- quantidades `Long.MAX_VALUE + 1`, `2^127` e decimal com centenas de dígitos;
- cadeia linear 20.000 e 100.000, respeitando orçamento configurado;
- grafo largo com 1k, 10k e 100k rotas;
- diamond DAG com coproduto compartilhado;
- output primário + múltiplos subprodutos determinísticos;
- subproduto que satisfaz dependência posterior;
- bônus probabilístico que nunca é prometido;
- dois targets craftáveis do mesmo padrão;
- alternatives com estoque compartilhado e conflito global;
- fuzzy com componentes diferentes e política exata/fuzzy;
- duas ferramentas danificadas com usos restantes distintos;
- recipiente retornável encadeado;
- catalisador compartilhado entre rotas concorrentes;
- feedback conservativo com mais de um estado interno;
- feedback com perda em múltiplas voltas;
- ciclos cruzados entre três SCCs;
- emitter infinito, emitter limitado e emitter instável recusado;
- item + fluido no mesmo plano;
- chave com componentes/NBT grande no limite e acima do limite;
- mudança de revisão em captura, solução e antes do commit;
- cancelamento em cada fase e a cada checkpoint relevante;
- timeout cooperativo e engine deliberadamente não cooperativo;
- grid unload/reload, split/merge e provider offline;
- duas, oito e 32 grids concorrentes, incluindo uma grid hostil;
- fila global cheia sem fallback explosivo no server thread;
- deduplicação de requests iguais com cancelamento independente dos callers;
- cache frio/quente, invalidação seletiva e ausência de snapshot parcial;
- menor/maior versão AE2 suportada;
- save/reload de job planejado e refund após receita desaparecer.

### 10.3 Corpus especializado inspirado no AE2-VM

Adicionar uma trilha própria para medir as áreas em que o AE2-VM é forte:

- Fibonacci profundo com pedidos `10^3`, `10^6` e `10^9`;
- DAG diamond com subárvores compartilhadas e diferentes níveis de fan-out;
- cadeia de 24, 32, 128, 2.048 e 20.000 níveis;
- cold compile, warm compile, cold cache e warm cache separados;
- repetição do mesmo target com estoque mudando entre requests;
- invalidação seletiva depois de provider/pattern entrar ou sair;
- patterns com multiplicadores grandes e potências de dois;
- comparação de expansão por caminho versus propagação agregada;
- pico de memória e tamanho do cache após muitos targets distintos;
- concorrência na mesma grid para verificar serialização/starvation;
- muitas grids simultâneas para verificar contenção global;
- pedido acima de `Long.MAX_VALUE`, distinguindo cálculo exato da capacidade da
  fronteira AE2;
- reprodução obrigatória de `multi-dag/fibonacci/minimum`, que deve retornar
  plano correto ou `DECLINE`, nunca falso positivo.

O relatório deve separar tempo de compilação, execução, replay, conversão AE2 e
cache. Um resultado warm-cache não pode ser comparado com cold-cache do outro
planner.

### 10.4 Três modos e oráculos

Para cada caso aplicável:

- `MISSING`: provar inviabilidade e validar que adicionar os faltantes relatados
  torna o caso viável;
- `MINIMUM`: fornecer somente um conjunto mínimo conhecido;
- `UNBOUNDED`: fornecer folhas em quantidade suficientemente grande.

Oráculos obrigatórios:

- replay passo a passo;
- conservação por chave;
- não negatividade do estoque em todos os passos;
- target produzido na quantidade pedida;
- execuções inteiras e não negativas;
- outputs probabilísticos fora da promessa;
- alocação física única de fuzzy/reutilizáveis;
- conjunto de faltantes reexecutável;
- comparação com mínimo conhecido quando computável;
- determinismo sob permutações.

## 11. Qualidade de faltantes

Não basta dizer “faltam materiais”. O plano deve otimizar e explicar:

- conjunto mínimo ou fronteira de soluções mínimas para corpus pequeno;
- `missingOverhead = custo relatado / menor custo válido`;
- pesos configuráveis apenas para diagnóstico, nunca escondendo unidades;
- separação entre faltante consumível e seed/reutilizável;
- nenhuma inclusão de subproduto probabilístico como material garantido;
- replay automático após injetar exatamente os faltantes informados.

Gate canônico: overhead 1,0 nos casos com ótimo conhecido. Em grafos grandes
onde provar ótimo exceder o orçamento, retornar melhor conjunto validado junto
de limite inferior, gap e motivo do encerramento.

## 12. Performance e isolamento

Medir separadamente:

- tempo de `check`;
- captura total e maior fatia por tick;
- normalização/SCC;
- fast path;
- solver global;
- replay;
- conversão AE2;
- tempo ponta a ponta;
- bytes alocados e pico estimado/medido;
- tamanho do snapshot/cache;
- fila, wait time e ocupação de workers.

Testar cold/warm cache, sucesso/faltante/decline/timeout e carga concorrente.
O benchmark não deve executar dentro do server tick para medir algoritmo puro,
mas deve existir GameTest/soak separado para medir impacto real no tick.

Políticas:

- orçamento global de captura por servidor;
- orçamento e in-flight por grid;
- fair queue/round-robin para evitar starvation;
- [x] limite de cache por bytes, não só por quantidade de entradas
  (`planner.snapshot.cacheBytes`, 128 MiB default, com evicção LRU);
- admissão anterior à alocação grande;
- circuit breaker por grid e por engine;
- nenhuma rejeição por fila pode criar pico maior via fallback síncrono;
- métricas p50/p95/p99 e histogramas limitados em memória.

## 13. Observabilidade e experiência do jogador

Expor por comando/API e, depois, UI:

- engine escolhido e cadeia tentada;
- fase atual e tempo por fase;
- revisão do snapshot;
- nós, arestas, SCCs e rotas alternativas;
- cache hit/miss, deduplicação e fila;
- operações, backtracks e gap do solver;
- razão exata de decline/fallback;
- faltantes separados por consumível/seed/ferramenta;
- cortes de ciclo e rota escolhida;
- status do circuit breaker/quarentena;
- impacto máximo observado no tick principal.

Logs detalhados devem ser opt-in/rate-limited. Métricas não podem reter AEKeys,
NBT ou referências à grid após lifecycle.

**Estado em 2026-09-17:** a lista acima é o alvo, e o que existe hoje é um recorte dela.
`/raishxcore planner` (nível 2) imprime uma linha JSON por planner vivo com revisão do
snapshot, cache hit/miss, fila e workers, deduplicação, backpressure, estado do circuit
breaker, contadores e histogramas da captura, e o último plano com operações, profundidade
e tempo. O payload é só números: nenhum `AEKey`, NBT ou referência à grid sobrevive ao
lifecycle, e o comando é opt-in por natureza. O relatório existia e estava testado e não
tinha chamador nenhum, que é o mesmo que não existir.

O primeiro item da lista que é sobre a *decisão* já existe no motor:
`CraftingPlan.shortage()` devolve os faltantes separados em consumível, seed e portador, com as três
chaves disjuntas cobrindo exatamente o faltante plano. Uma chave que falta por mais de um motivo é
cobrada na ordem consumido, desgastado, devolvido — o que é realmente consumido é o mais acionável de
dizer, e só o que não se explica assim é chamado de seed. Escrever isso revelou um defeito: um
catalisador decaente sem estoque nenhum era cobrado duas vezes, uma pela checagem de presença e outra
pelo saque do decaimento.

O split **é renderizado**: sai no comando como `missingConsumable`, `missingSeed` e
`missingCarrier` mais a contagem de chaves de cada um, e a identidade dos itens fica de fora
deliberadamente para o payload continuar sendo números. A primeira versão acumulava a demanda a cada
saque, e o gate de alocação cobrou sete e meio por cento num caso cujo plano é completo; a segunda
derivava varrendo o plano uma vez por chave faltante, e o gate cobrou vinte e sete por cento numa
cadeia com uma folha faltante. A terceira deriva numa passada só e só para as chaves que faltam, e
ficou em 1,11x e 1,10x. A forma do grafo também sai: `graph.keys`, `graph.patterns` e `graph.edges`, contados uma vez na
compilação do snapshot e não sob demanda, porque uma pergunta de operador não pode fazer todo plano
pagar para ser respondível. Os SCCs ficam de fora: o adaptador de ciclos reconhece uma volta simples
de rota única e não constrói componentes, então não há o que contar.

Também faltam a rota escolhida e por quê, e os cortes de ciclo. O `gap do solver` fica sem sentido enquanto a Fase 4 não
existir, e está registrado como tal lá.

## 14. Compatibilidade, API e rollout

### Compatibilidade AE2

- fixar range realmente testado;
- CI na menor e maior versão suportada;
- testes de contrato para cada accessor/mixin;
- adapter por versão quando a API interna divergir;
- kill-switch independente para planner, captura e CPU compartilhada;
- falha de compatibilidade deve desativar o recurso com diagnóstico, não impedir
  o servidor de iniciar, quando tecnicamente seguro.

### Rollout

1. modo shadow: RaishxCore calcula, replaya e compara, mas AE2 executa o plano
   original;
2. opt-in por servidor/grid para capacidades já certificadas;
3. fallback obrigatório para semântica ainda não suportada;
4. coleta de diagnóstico anonimizado/local, sem telemetria externa automática;
5. default-on somente após soak e GameTests do modpack real;
6. remoção de flags antigas apenas depois de uma janela de deprecação.

## 15. Gates de aceitação

### Gate P — paridade

Estado em 2026-09-17: o corpus passou de 27 para 66 casos em 22 grupos, todos nos três modos de
material, e a comparação com o Thunderbolt V2 deixou de ser uma promessa e virou um relatório
(`plannerCapabilityMatrix`). Nenhuma afirmação de paridade é feita contra o AE2, que não é medido.

- [x] Zero falso positivo, erro ou timeout não cooperativo: 0 falsos positivos, 0 falsos negativos,
      0 erros de motor e 0 timeouts não cooperativos nos 66 casos, com a classificação publicada.
- [x] Replay e determinismo aprovados: todo caso é reproduzido pelo oráculo comum, inclusive pelo
      caminho de reabastecimento, e o harness roda cada caso também com a ordem de declaração
      invertida.
- [x] Integração AE2 equivalente comprovada por GameTests: 10/10 GameTests verdes, com asserção do
      banner de conclusão para que uma falha de carregamento não passe como sucesso.
- [ ] 33/33 casos Thunderbolt `SUPPORTED` no RaishxCore: a suíte de referência não está importada e o
      corpus é próprio, então esta afirmação não pode ser feita. O que a matriz publica é o nível de
      alegação por caso: 44 completos e 22 de falta, contra 37 e 26 com três sem resposta.

### Gate S — semântica superior

Estado em 2026-09-17: quatro dos cinco itens têm evidência; o corpus adicional segue aberto.

- [x] Faltantes mínimos nos casos canônicos: os 22 casos de falta reportam exatamente o mínimo
      conhecido, `missingOverhead = 1.000`, e a lista é assertada em vez de só impressa.
- [x] Probabilidade, emitters, remainder, fuzzy, durabilidade e feedback têm contratos explícitos e
      testes: cada uma tem grupo próprio nos três modos de material. Probabilidade é resolvida
      descartando a rolagem, porque uma saída de chance não é promessa a um pedido determinístico.
- [x] Motivos de decline e falha são estruturados e localizáveis: a admissão devolve
      `Check.reject(reason)`, o planejamento devolve um `PlanningResult.Status` tipado, e a taxonomia
      do harness separa classes em vez de um booleano.
- [x] `BigInteger` extremo aprovado sem truncamento: pedidos acima de 2^63 têm asserção de
      `bitLength()` em `IterativeCraftingPlannerTest`, `PlannerConservationTest` e
      `RaishxCoreCapabilityPlannerTest`. Isto não vira caso de corpus: o modo `UNBOUNDED` é um teto
      fixo de 10^12 e não comporta um pedido maior, e forçá-lo mudaria todos os outros casos.
- [ ] Corpus adicional completo aprovado: a lista em `docs/planner-differential-corpus.md` (item mais
      fluido, cancelamento de lifecycle, grids concorrentes) continua aberta.

### Gate O — operação superior

Estado em 2026-09-17: captura cooperativa, orçamento global, cancelamento por
fase, fatia interruptível dentro de uma chave e histogramas de p50/p95/p99
existem e têm testes. O alvo de 2 ms foi medido em carga sintética
(`plannerCaptureSlices`), mas **não** foi confirmado in-game num servidor real,
e o soak com grids reais continua pendente.

- [x] Captura incremental com fatia interruptível e orçamento global: uma fatia
      termina entre dois padrões, nunca entre chaves apenas, então o tempo que
      uma grid retém o server thread não cresce com o número de padrões de uma
      chave (bound determinístico verificado por unit test e pelo harness).
- [ ] Fatia p95 ≤ 2 ms por grid/tick confirmada em jogo: com 20 µs simulados por
      chamada de grid, o p95 medido pelo harness ficou em 0,8 ms de uma chave com
      1 até 10 000 padrões, e o p95 do próprio maquinário ficou em 0,2 ms. Em jogo
      os histogramas já medem: a primeira captura de um processo custa 2-4 ms por
      fatia (class loading e JIT, fase de padrão 2,66 ms e publicação 2,16 ms numa
      grid de duas chaves) e a segunda, já quente, cai para ~0,3 ms por fatia com
      p50 de 0,8 ms. Falta uma sessão real com grid grande para confirmar o alvo.
- [x] Multi-grid não apresenta starvation nem contaminação de circuit breaker:
      o soak com três grids AE2 reais disputando um orçamento por tick, uma delas
      com provedor que queima 4 ms dentro de uma única chamada de adapter, fecha
      com 15 fatias em 8 ticks, todas as requisições planejadas pelo Core, nenhuma
      captura pendente, nenhum backpressure e nenhum circuit breaker disparado em
      nenhuma grid (o soak longo com save real entra no Gate R).
- [x] Cancelamento/lifecycle em todas as fases da captura e do planejamento
      (sessões multi-engine entram no R2.7).
- [x] Engine não cooperativo isolado sem bloquear AE2 ou nova grid: o circuit breaker é por grid,
      com limiar e cooldown configuráveis, testado em `PlannerCircuitBreakerTest`, e um planejador que
      não coopera é classificado como `NON_COOPERATIVE_TIMEOUT` pelo runner em vez de segurar a fila.
      A sessão multi-engine completa continua no R2.7.
- [x] Métricas p50/p95/p99, fila, cache e memória disponíveis: histogramas de
      fatia e de tick (ambos server-wide, uma única instância de
      `CaptureMetrics`), acumuladores por fase da
      fatia, fila, cache, bytes e orçamento restante em `Diagnostics`; não há
      exportador externo de métricas.

### Gate D — desempenho diferencial

- [x] Benchmark é gate de CI com tolerância estatística documentada: `plannerBenchmark` reprova a
      build, e o próprio gate documenta as duas tolerâncias e por que a alocação não é byte-exata
      entre máquinas.
- [ ] Harness executa RaishxCore, Thunderbolt V2, AE2-VM e AE2 pelo caminho correto: a matriz tem duas
      colunas. O AE2-VM precisa de uma credencial que este checkout não tem, e o caminho do próprio
      AE2 não está ligado.
- [x] Ambiente e commits congelados no relatório: o relatório imprime o commit do Core, a JVM e a
      máquina, e a revisão da referência deixou de ser só impressa — ela é declarada e o relatório se
      recusa a ser gerado contra qualquer outra, com a mensagem dizendo o que fazer. Um checkout
      ausente é reportado como não congelado em vez de fatal, para a metade de capacidade ainda rodar
      onde não há referência ao lado. O relatório não é anexado a um release, o que é item do Gate R.
- [ ] Critérios do Nível C atendidos: depende dos dois itens acima.
- [ ] Cold/warm compile e cold/warm cache são comparados separadamente: o benchmark já separa
      `cached_graph_plan` de `graph_and_plan`; compilação fria e quente não é medida.
- [ ] O falso positivo conhecido do AE2-VM é rejeitado ou resolvido corretamente: bloqueado pela
      credencial.

### Gate R — release

- [x] Migração/config/kill-switch documentados: `docs/planner-configuration.md` traz
      `planner.enabled` como kill switch, todas as chaves numéricas com faixa validada e o
      comportamento de recarga de cada uma. A parte de migração entre versões continua sem documento,
      e é por isso que este item fica marcado só até onde o documento vai.
- [ ] Menor e maior AE2 suportado verdes: o CI roda as duas pontas da faixa; a menor não chega a
      carregar no NeoForge, então ela é compilada e não executada.
- [x] UFO Future verde como consumidor real: o `settings.gradle` do UFO faz
      `includeBuild('../RaishxCore')` com substituição do módulo, então ele compila e roda contra a
      árvore local do Core e não contra o artefato publicado. Verificado em 2026-09-17 contra o motor
      de então: **233 testes unitários e 30/30 GameTests verdes**, os GameTests exercitando a ponte
      com uma grid AE2 real, que é onde a composição com o adaptador de ciclos e o comando de
      diagnóstico vivem. O que continua fixado num Core anterior é o artefato **publicado**, e isso só
      importa no momento do release do UFO, não no desenvolvimento.
- [ ] Soak com múltiplas grids e save real aprovado.
- [ ] Relatório público diferencia fatos, limitações e capacidades opcionais: a matriz faz isso para
      capacidade; falta a parte de release.

Somente após P + S + O + D + R será permitido marcar “superioridade
comprovada”. Antes disso, a documentação deve usar “em desenvolvimento”,
“paridade parcial” ou “candidato”.

## 16. Sequência de implementação sugerida

### R2.1 — especificação e harness

Concluído e verificado neste recorte (`docs/planner-differential-corpus.md`):

- [x] representação neutra de cenários de capacidade, com todos os tipos de
      input/output/estoque previstos e `BigInteger` ponta a ponta;
- [x] corpus independente de 17 grupos × 3 modos, recriado como especificação
      comportamental sem importar classes de produção de outro planner;
- [x] replay/oráculo comum: conservação por recurso, estoque não negativo,
      execuções inteiras e não negativas, produção da quantidade pedida,
      validade dos faltantes, sobreprodução, subprodutos e determinismo;
- [x] runner pelo caminho público de produção, com deadline rígido e separação
      entre timeout cooperativo e não cooperativo;
- [x] taxonomia de oito classificações mantidas em colunas separadas;
- [x] harness determinístico e gate de CI (`./gradlew plannerDifferential`), com
      teste que detecta propositalmente um plano inválido;
- [x] corrigir o defeito de ordenação de coproduto em 2.2: ordem canônica de
      inputs resolve entradas com rota antes de entradas só-subproduto.

Pendente no R2.1:

- [ ] congelar o corpus AE2-VM e escrever os adapters diferenciais de Thunderbolt
      V2 e AE2 atrás do mesmo contrato;
- [ ] produzir o relatório baseline dos quatro planners: RaishxCore,
      Thunderbolt V2, AE2-VM e AE2 original;
- [ ] corpus adicional do Raishx descrito em 10.2.

### R2.2 — captura cooperativa

Concluído e verificado neste recorte (`docs/planner-cooperative-capture.md`):

- [x] state machine incremental e retomável, independente do AE2, que nunca
      publica estado parcial;
- [x] orçamento por grid (2 ms/512 arestas por fatia) e orçamento global por tick
      (4 ms) com rodízio entre grids e estouro debitado integralmente;
- [x] cache por revisão e por bytes, além da contagem de entradas;
- [x] cancelamento em todas as transições: revisão, grid, jogador, lifecycle,
      kill-switch, orçamento e cancelamento do próprio chamador;
- [x] testes de mutação durante captura: unitário (cancelamento e recusa entre
      fatias) e GameTest (mudança de provider durante captura aberta);
- [x] futura adiada entregue ao AE2, com fallback do AE2 somente depois de
      descartar a tentativa inteira e com o motivo registrado no status;
- [x] testes de mutação durante captura e de captura que atravessa ticks.

Concluído no segundo recorte do R2.2:

- [x] fatia dentro de uma chave: cursor por padrão, com o adapter respondendo uma
      chave (`patternCount`) e um padrão por vez (`patternAt`), de modo que uma
      chave gorda é capturada em várias fatias em vez de uma única chamada;
- [x] contabilidade determinística da fatia: arestas realmente consumidas e
      tempo por fase (chave, padrão, publicação) expostos pelo maquinário;
- [x] `CaptureSliceMetrics`: histogramas limitados de fatia e de tick, com
      p50/p95/p99 conservadores (borda superior do balde) e acumuladores por
      fase, conectados a `Diagnostics`;
- [x] harness `plannerCaptureSlices` que dirige o maquinário de produção em
      fatias, mede p50/p95/p99 por grid e por tick, compara cada captura fatiada
      com a captura sem limite e falha se qualquer fatia crescer além do seu
      orçamento mais uma cauda atômica;
- [x] rodízio entre grids medido pelo harness com quatro capturas concorrentes,
      uma delas muito mais pesada, sem starvation e sem captura perdida;
- [x] GameTest que prova, numa grid AE2 real, que uma chave com três rotas é
      capturada em várias fatias e ainda planeja exatamente.

Concluído no terceiro recorte do R2.2:

- [x] soak multi-grid com grids AE2 reais e um provedor deliberadamente lento
      (4 ms dentro de uma chamada de adapter), registrado primeiro para que um pump
      sem rodízio deixasse as outras grids sem fatia; todas terminam, sem
      backpressure e sem contaminação de circuit breaker;
- [x] medir em jogo, não só em carga sintética: dois alvos na mesma grid, com a
      leitura fria e a quente registradas separadamente, por fase (chave, padrão,
      publicação) e com teto de fumaça de 50 ms garantido pelo teste.

Pendente no R2.2:

- [ ] confirmar a fatia p95 de 2 ms in-game numa grid grande (histogramas prontos,
      medição ao vivo pendente);
- [ ] orçamento de tick recarregável sem reiniciar o servidor;
- [ ] cobrir o comportamento de captura no corpus diferencial, não só em unit
      tests e GameTests.

### R2.3 — modelo semântico

- novos tipos de input/output/estoque;
- normalização e fingerprints;
- adapters AE2 exatos;
- manter fast path para padrões simples.

### R2.4 — alternatives, fuzzy e remainder

- matching capturado;
- alocação física única;
- recipientes retornáveis;
- testes com componentes e fluidos.

### R2.5 — catalisadores, durabilidade e feedback

Catalisadores e usos finitos estão implementados e verificados; o desenho das quatro
famílias de ciclo, com a aritmética de seed, o contrato de balanço e a ordem de tarefas
que cada uma exige, está em [planner-feedback-cycles.md](planner-feedback-cycles.md).

- estoque reutilizável;
- usos finitos;
- SCC e seeds;
- replay de ordem executável.

### R2.6 — solver global

- conflitos multi-rota;
- objetivo lexicográfico;
- melhor plano validado sob budget;
- comparação de backend próprio/CP-SAT.

### R2.7 — multi-engine e contenção

- sessões candidatas;
- timeout cooperativo;
- quarentena;
- fallback integral e observável.

### R2.8 — superioridade diferencial

- otimização orientada por profiling;
- gates estatísticos;
- soak multi-grid;
- relatório final e decisão de default-on.

## 17. Riscos e decisões abertas

- CP-SAT melhora cobertura, mas adiciona runtime nativo, tamanho e diferenças
  de plataforma; decidir apenas após benchmark do solver próprio limitado.
- Snapshot mantido por eventos é mais barato, mas arriscado se algum evento do
  AE2 não for observado; captura incremental é o baseline seguro.
- A fatia da captura termina entre chaves: uma única chave com muitos padrões pode
  exceder seu orçamento de 2 ms. O estouro é debitado do orçamento compartilhado do
  tick, mas o p95 só poderá ser prometido depois de fatiar dentro da chave.
- Otimizar faltantes pode competir com latência. O contrato deve permitir gap
  explícito em grafos grandes, nunca lista aparentemente ótima sem prova.
- Planejar quantidade `BigInteger` não significa que toda fronteira AE2 aceite
  essa quantidade de uma vez; adapters devem particionar ou recusar claramente.
- Probabilidade exige separar promessa de bônus. “Valor esperado” não pode
  satisfazer pedido determinístico do crafting CPU.
- Ciclos positivos podem ser válidos em mods específicos, mas habilitá-los no
  planner comum cria risco de material infinito. Permanecem recusados até haver
  engine e política próprios.
- Superioridade deve ser revalidada quando Thunderbolt, AE2, Java ou o corpus
  mudar. O relatório sempre nomeia a baseline exata comparada.
- AE2-VM é LGPL-3.0 e deve ser tratado como implementação externa. Usar ideias
  arquiteturais e cenários comportamentais independentes; qualquer reutilização
  literal de código exigiria análise e cumprimento explícito da licença.

## 18. Definition of Done de cada capability

Uma capability só é considerada suportada quando possui:

1. tipo/contrato público documentado;
2. captura AE2 real, sem fixture exclusiva;
3. unit tests puros em todos os modos aplicáveis;
4. replay de conservação e execução ordenada;
5. caso de faltante e diagnóstico;
6. cancelamento e limite de custo;
7. determinismo sob permutação;
8. GameTest quando depende de comportamento do AE2/Minecraft;
9. benchmark de custo e memória;
10. entrada na matriz diferencial e documentação de limites.

Ter código que “funciona em um exemplo” não atende esta definição.

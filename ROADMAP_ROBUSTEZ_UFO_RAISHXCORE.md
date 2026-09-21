# Roadmap de robustez — UFO Future e RaishxCore

Este documento registra a ordem de trabalho decidida após a auditoria de
2026-09-16. A prioridade é consolidar a fundação antes de introduzir novas
mecânicas. Ideias de gameplay permanecem reservadas para updates posteriores.

Relatório de origem: `AUDITORIA_UFO_RAISHXCORE_2026-09-16.md`.

## Princípio de execução

Cada marco só avança quando seus critérios automatizados e, quando aplicável,
seu roteiro in-game estiverem aprovados. Alterações do RaishxCore sempre devem
ser validadas também pelo UFO Future como consumidor real.

## Marco 1 — release hardening

- [x] Remover caches de datagen do JAR do UFO.
- [x] Incluir licença e notices nos artefatos dos dois projetos.
- [x] Gerar sources JAR do UFO.
- [x] Atualizar documentação com nome, modid, artefato e versões atuais.
- [x] Criar CI próprio do RaishxCore.
- [x] Aplicar validação e rate budget aos custom packets C2S de player/item.
- [x] Retirar a superfície incompleta do side config; reintroduzir somente com
      persistência, UI, capability filtering e testes.
- [ ] Criar testes específicos da armadura modular.
- [ ] Tornar custos e limites críticos da armadura configuráveis no servidor.
- [ ] Executar QA de mundo 2.x e do modpack real.

Gate: builds, testes e GameTests verdes; artefatos limpos e reproduzíveis;
nenhum pacote C2S fora da política central; documentação correspondente ao
código publicado.

## Marco 2 — planner do RaishxCore

Especificação detalhada de paridade e superioridade verificável:
[`RaishxCore/docs/planner-superiority-roadmap.md`](RaishxCore/docs/planner-superiority-roadmap.md).
O objetivo não é apenas vencer um benchmark simples: o planner deve se tornar
um superset semântico do Thunderbolt V2, preservar quantidades `BigInteger`,
reduzir impacto no server thread, melhorar a qualidade de faltantes e provar
tudo no mesmo corpus diferencial. O AE2-VM (`TaoLe-si/AE2-VM`, baseline
inspecionada `b03afe75`) entra como referência especializada em bytecode, VM
iterativa, agregação de DAG e cache/JIT; seu falso positivo conhecido em
`multi-dag/fibonacci/minimum` vira caso obrigatório de regressão.

- [x] Remover a captura monolítica do grafo do tick principal e implementar
      captura cooperativa por tick: state machine retomável, fatia por grid,
      orçamento compartilhado por tick (rodízio entre as capturas de cada grid,
      não entre grids), cache por revisão e por bytes,
      cancelamento em todas as transições e fallback do AE2 somente depois de
      descartar a tentativa inteira
      (`RaishxCore/docs/planner-cooperative-capture.md`).
- [x] Tornar a fatia interrompível dentro de uma chave (cursor por padrão), de modo
      que o tempo retido no server thread não cresça com o número de padrões de uma
      chave, e tornar a duração observável: histogramas limitados de fatia e de tick
      com p50/p95/p99, acumuladores por fase e o harness `plannerCaptureSlices`, que
      verifica os bounds determinísticos e reporta os percentis medidos.
- [x] Deduplicar requests equivalentes em andamento com futures independentes.
- [x] Cancelar jobs obsoletos em revisão, alteração/unload da grid, logout,
      desativação do planner e server stop.
- [x] Configurar workers, fila, timeout, nós, arestas e memória estimada.
- [x] Adicionar circuit breaker e backpressure por grid.
- [ ] Expor métricas p50/p95/p99, cache, fila, fallback e timeout: contadores de
      execução, deduplicação, cancelamento, workers, fila e captura concluídos,
      histogramas de fatia e de tick com p50/p95/p99 e temporização por fase já estão
      em `Diagnostics`; falta um exportador externo.
- [ ] Confirmar in-game a fatia p95 de 2 ms por grid/tick: medido em 0,8 ms sob custo
      simulado de 20 µs por chamada de grid, de 1 a 10 000 padrões numa única chave, e em
      jogo os histogramas já separam a primeira captura fria de um processo (2-4 ms por
      fatia, class loading e JIT) da segunda, quente (~0,3 ms por fatia); falta uma sessão
      real com grid grande.
- [x] Testar múltiplos grids e requests concorrentes: soak com três grids AE2 reais
      disputando um orçamento por tick, uma delas com provedor que queima 4 ms dentro de
      uma única chamada de adapter e registrado primeiro, para que um pump sem rodízio
      deixasse as outras grids sem fatia. Todas as requisições foram planejadas pelo Core,
      sem starvation, sem backpressure e sem contaminação de circuit breaker. O soak longo
      com save real continua no gate de release.
- [ ] Transformar o benchmark em gate contra regressões.
- [x] Criar corpus neutro de capacidade, oráculo de replay comum, runner pelo
      caminho de produção, taxonomia de oito classificações e gate de CI
      (`plannerDifferential`, `RaishxCore/docs/planner-differential-corpus.md`).
- [x] Classificar a baseline do RaishxCore isoladamente: 27/27 capacidades
      obrigatórias suportadas, 24 casos de limitação recusados na admissão,
      nenhum defeito confirmado em aberto.
- [ ] Executar o mesmo corpus diferencial de capacidade e desempenho contra AE2
      Thunderbolt V2 e AE2-VM antes de afirmar superioridade de qualidade ou velocidade
      (corpus, oráculo e gate existem; só o lado RaishxCore roda hoje).
- [ ] Reproduzir os 11 grupos/33 casos de referência do Thunderbolt pelo caminho
      de produção, com replay e três modos de materiais (recriados como
      especificação independente de 17 grupos/51 casos; falta o adapter de referência).
- [x] Corrigir o falso negativo de ordenação de coproduto revelado pelo corpus:
      uma entrada com rota selecionável passa a ser resolvida antes de uma
      entrada que só existe como subproduto determinístico.
- [ ] Modelar papéis explícitos de saída para coletar um subproduto cujo padrão
      produtor não é exigido por nenhuma outra rota, em vez de recusar.
- [ ] Modelar explicitamente alternatives/fuzzy, remainders, catalisadores,
      durabilidade finita, emitters, outputs probabilísticos e feedback/SCCs.
- [ ] Adicionar solver inteiro global limitado para conflitos multi-rota, sem
      abandonar o fast path iterativo para DAGs comprovadamente simples.
- [ ] Garantir `BigInteger` ponta a ponta e testar valores acima de `long`.
- [ ] Produzir faltantes mínimos nos casos canônicos e expor gap quando o ótimo
      não puder ser provado dentro do orçamento.
- [ ] Criar pipeline multi-engine com timeout cooperativo, isolamento,
      quarentena e fallback integral observável.
- [ ] Passar corpus adicional Raishx: multi-grid, lifecycle, probabilidade,
      item+fluido, split/merge, cache, fila e engines hostis.
- [ ] Publicar relatório reproduzível com commits, ambiente, p50/p95/p99,
      alocação, impacto no tick, qualidade de faltantes e matriz de capacidades.
- [ ] Separar cold/warm compile e cold/warm cache na comparação com AE2-VM;
      medir cadeias profundas, DAG compartilhado, invalidação e concorrência.
- [ ] Validar menor e maior versão suportada do AE2.

Gate: nenhuma captura individual pode consumir um tick inteiro (implementado em
nível de chave: uma chave excepcionalmente pesada ainda pode exceder a própria
fatia e é debitada integralmente do orçamento compartilhado); carga
concorrente deve permanecer limitada e observável; conservação continua
provada em todos os cenários. “Superioridade comprovada” exige todos os gates
P/S/O/D/R do documento técnico; até lá, usar apenas “paridade parcial” ou
“candidato”.

## Marco 3 — transações e persistência

- [ ] Criar fluxo comum `simulate -> reserve -> validate -> commit`.
- [ ] Suportar rollback, refund, commit parcial e idempotency key.
- [ ] Criar journal persistente e recuperação após crash.
- [ ] Unificar item, fluido, chemical, FE, AE e quantidades exatas.
- [ ] Versionar todo estado persistente com migradores sequenciais.
- [ ] Manter fixtures douradas de cada release suportado.
- [ ] Limitar tamanhos vindos de NBT e rede.

Gate: testes de crash/unload provam ausência de dupe e perda em todas as fases
da transação.

## Marco 4 — toolkit de multiblocos no Core

- [ ] Extrair definição, compilador, constraints e orientação.
- [ ] Extrair scanner com diagnóstico explicável.
- [ ] Extrair membership index e invalidação por eventos.
- [ ] Extrair snapshots e sync incremental.
- [ ] Extrair auto-build, holograma e preview.
- [ ] Extrair ports/adapters reutilizáveis.
- [ ] Converter o UFO para consumidor, removendo duplicações.

Gate: uma definição canônica alimenta formação, scanner, highlight, auto-build,
JEI/EMI e GuideME sem topologia duplicada.

## Marco 5 — scheduler, segurança e observabilidade

- [ ] Scheduler cooperativo com orçamento por tick e anti-starvation.
- [ ] Jobs pausáveis, canceláveis e persistentes.
- [ ] Ownership, equipes, permissões e adapters de claims.
- [ ] Diagnóstico por comando, JSON e marcadores JFR.
- [ ] Motivo estruturado de bloqueio por máquina.
- [ ] Métricas por grid, máquina e tipo de trabalho.

Gate: operações caras respeitam orçamento global e por máquina; ações remotas e
destrutivas obedecem ownership e deixam diagnóstico verificável.

## Marco 6 — validação como plataforma

- [ ] Publicar testkit para addons.
- [ ] Criar segundo addon pequeno sem acessar internals do UFO.
- [ ] Corrigir contratos revelados pelo segundo consumidor.
- [ ] Verificar compatibilidade binária da API.
- [ ] Publicar RaishxCore 1.0 somente após essa validação.

## Backlog de updates futuros — mecânicas

Estas ideias não entram antes da consolidação dos marcos anteriores:

- painel agregado de operações da rede;
- blueprints copiáveis de multiblocos;
- contratos de produção com distribuição automática de jobs;
- loadouts da armadura modular;
- Stellar Nexus com eventos dinâmicos e risco previsível;
- terminal portátil UFO;
- manutenção baseada em telemetria;
- progressão por domínio tecnológico;
- recuperação guiada de estruturas e jobs;
- API data-driven/KubeJS para tiers, coolants e simulações.

## Sequência de versões sugerida

1. UFO `3.0.0-alpha.2`: hardening do Marco 1.
2. RaishxCore `0.1.0-alpha.3`: CI, publicação e higiene de API.
3. RaishxCore `0.2.0`: planner robusto.
4. RaishxCore `0.3.0`: transações e migrações.
5. RaishxCore `0.4.0`: toolkit de multiblocos.
6. RaishxCore `0.5.0`: scheduler, diagnóstico e ownership.
7. UFO `3.0.0-beta.1`: migração integral para o Core.
8. RaishxCore `0.9.0`: validação pelo segundo addon.
9. RaishxCore `1.0.0` e UFO `3.0.0`: APIs e releases estáveis.

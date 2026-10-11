# Lsmsnksoslalql

## Готовая 3D-модель — файлы, без эмулятора

Модель девушки Tripo обработана через GitHub Actions. Результаты сохранены в ветке
`arena/1e52fcec-lsmsnksoslalql`, в папке [`handoff/rigged`](handoff/rigged/).

- **[Скачать GLB с костями и анимациями](https://github.com/KamiSakyy/Lsmsnksoslalql/raw/refs/heads/arena/1e52fcec-lsmsnksoslalql/handoff/rigged/anime-girl-rigged.glb)** — около 18 МБ, 160 000 треугольников, встроенные текстуры.
- **[Скачать FBX](https://github.com/KamiSakyy/Lsmsnksoslalql/raw/refs/heads/arena/1e52fcec-lsmsnksoslalql/handoff/rigged/anime-girl-rigged.fbx)** — около 11 МБ, скелет, текстуры и клипы.
- **[Полный пакет: исходная детализация GLB + Blender + FBX](https://github.com/KamiSakyy/Lsmsnksoslalql/actions/runs/38112065533/artifacts/11692127202)** — артефакт `anime-girl-rigged`. Полный GLB сохраняет исходные 1 159 795 вершин, 1 981 742 треугольника и текстуры без упрощения. Артефакт GitHub хранится 90 дней; скачайте его для долговременного хранения.
- [Инструкция и ограничения](handoff/rigged/README_RU.md).

**48 костей**, включая тело, волосы и одежду. **Пять процедурных клипов**:
`Idle`, `Walk_InPlace`, `Run_InPlace`, `Wave`, `Dance`. Для скачивания и использования
модели сайт или эмулятор не требуется.

[Итоговая сборка Actions](https://github.com/KamiSakyy/Lsmsnksoslalql/actions/runs/38112065533)
завершилась успешно. Оба GLB проверены официальным валидатором Khronos: **0 ошибок**.
Непривязанных вершин — **0**, не более четырёх влияний на вершину.
Машинные отчёты: [rig-report.json](handoff/rigged/rig-report.json),
[khronos-validation.json](handoff/rigged/khronos-validation.json).

## Инструменты и повторная сборка

Blender 4.2 LTS (`bpy`), NumPy и SciPy: скелет под исходную асимметричную позу,
геодезическая привязка по поверхности, капсульные веса, сглаживание и перенос весов
на исходную высокодетализированную модель. Нулевые/неортогональные касательные
экспорта исправляются без удаления нормал-карты.

Workflow: [rig-model.yml](.github/workflows/rig-model.yml), только текущая ветка.
Изменение скриптов/профиля запускает сборку; `workflow_dispatch` поддерживает
необязательный `render_previews`. По умолчанию генерируются файлы модели без рендеров.
Скрипты — [`tools/rigging/`](tools/rigging/); 18 тестов — [`tests/rigging/`](tests/rigging/).

Это стартовый автоматический FK-риг, не ручной production-риг: нет мимики, отдельных
костей пальцев и физической симуляции ткани. Большие движения могут требовать
коррекции весов и корректирующих форм. Клипы процедурные, не motion capture Mixamo.
Исходный ZIP, код сервера и его конфигурация не изменены и не используются пайплайном.

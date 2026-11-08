import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../api/api_client.dart';
import '../config.dart';
import '../models/event_summary.dart';
import '../theme/app_theme.dart';
import 'bookings_screen.dart';
import 'event_detail_screen.dart';

class EventsScreen extends StatefulWidget {
  const EventsScreen({super.key});
  @override
  State<EventsScreen> createState() => _EventsScreenState();
}

class _EventsScreenState extends State<EventsScreen> {
  final _api = ApiClient();
  late Future<List<EventSummary>> _future;
  String _query = '';
  String _category = 'All';

  @override
  void initState() {
    super.initState();
    _future = _api.availableEvents();
  }

  Future<void> _reload() async {
    setState(() => _future = _api.availableEvents());
    await _future;
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        body: SafeArea(
            child: FutureBuilder<List<EventSummary>>(
          future: _future,
          builder: (context, snapshot) {
            final events = snapshot.data ?? <EventSummary>[];
            final categories = [
              'All',
              ...{
                for (final e in events)
                  if (e.category?.isNotEmpty == true) e.category!
              }
            ];
            final filtered = events.where((e) {
              final q = _query.toLowerCase();
              return (_category == 'All' || e.category == _category) &&
                  (e.name.toLowerCase().contains(q) ||
                      e.hallName.toLowerCase().contains(q) ||
                      (e.category?.toLowerCase().contains(q) ?? false));
            }).toList();
            return RefreshIndicator(
              onRefresh: _reload,
              child: CustomScrollView(slivers: [
                SliverToBoxAdapter(
                    child: Padding(
                  padding: const EdgeInsets.fromLTRB(20, 24, 20, 8),
                  child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(children: [
                          const Expanded(
                              child: Text('Events',
                                  style: TextStyle(
                                      fontSize: 32,
                                      fontWeight: FontWeight.w900))),
                          IconButton.filledTonal(
                            tooltip: 'My tickets',
                            onPressed: () => Navigator.push(
                                context,
                                MaterialPageRoute(
                                    builder: (_) => const BookingsScreen())),
                            icon:
                                const Icon(Icons.confirmation_number_outlined),
                          ),
                        ]),
                        const Text('Book amazing events',
                            style: TextStyle(
                                color: AppColors.muted, fontSize: 16)),
                        const SizedBox(height: 24),
                        TextField(
                          onChanged: (value) =>
                              setState(() => _query = value.trim()),
                          decoration: const InputDecoration(
                              hintText: 'Search events or venues',
                              prefixIcon: Icon(Icons.search)),
                        ),
                        const SizedBox(height: 20),
                      ]),
                )),
                SliverToBoxAdapter(
                    child: SizedBox(
                        height: 48,
                        child: ListView.separated(
                          padding: const EdgeInsets.symmetric(horizontal: 20),
                          scrollDirection: Axis.horizontal,
                          itemCount: categories.length,
                          separatorBuilder: (_, __) => const SizedBox(width: 8),
                          itemBuilder: (_, i) => ChoiceChip(
                            label: Text(categories[i]),
                            selected: _category == categories[i],
                            onSelected: (_) =>
                                setState(() => _category = categories[i]),
                          ),
                        ))),
                const SliverToBoxAdapter(child: SizedBox(height: 18)),
                if (snapshot.connectionState != ConnectionState.done)
                  const SliverFillRemaining(
                      child: Center(child: CircularProgressIndicator()))
                else if (snapshot.hasError)
                  SliverFillRemaining(
                      child: _Message('${snapshot.error}', onRetry: _reload))
                else if (filtered.isEmpty)
                  SliverFillRemaining(
                      child: _Message(events.isEmpty
                          ? 'No events available right now.'
                          : 'No events match your search.'))
                else
                  SliverPadding(
                    padding: const EdgeInsets.fromLTRB(20, 0, 20, 28),
                    sliver: SliverList.separated(
                      itemCount: filtered.length,
                      separatorBuilder: (_, __) => const SizedBox(height: 18),
                      itemBuilder: (_, i) => _EventCard(event: filtered[i]),
                    ),
                  ),
              ]),
            );
          },
        )),
      );
}

class _EventCard extends StatelessWidget {
  const _EventCard({required this.event});
  final EventSummary event;

  @override
  Widget build(BuildContext context) => InkWell(
        borderRadius: BorderRadius.circular(20),
        onTap: () => Navigator.push(context,
            MaterialPageRoute(builder: (_) => EventDetailScreen(event: event))),
        child: Container(
          clipBehavior: Clip.antiAlias,
          decoration: BoxDecoration(
              color: AppColors.surface,
              borderRadius: BorderRadius.circular(20)),
          child:
              Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            SizedBox(
                height: 200,
                width: double.infinity,
                child: Stack(fit: StackFit.expand, children: [
                  EventPoster(imageUrl: event.imageUrl),
                  Positioned(
                      top: 14,
                      left: 14,
                      child: _Badge(event.category ?? 'Event')),
                  if (event.status == 'SOLD_OUT')
                    const Positioned(
                        top: 14,
                        right: 14,
                        child: _Badge('Sold out', color: Color(0xFFBD3939)))
                  else if (event.featured)
                    const Positioned(
                        top: 14,
                        right: 14,
                        child: _Badge('Featured', color: AppColors.orange)),
                ])),
            Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(event.name,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                            fontSize: 20, fontWeight: FontWeight.w800)),
                    if (event.tagline?.isNotEmpty == true) ...[
                      const SizedBox(height: 4),
                      Text(event.tagline!,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(color: AppColors.muted)),
                    ],
                    const SizedBox(height: 12),
                    _IconText(
                        Icons.calendar_today_outlined,
                        event.startAt == null
                            ? 'Date TBA'
                            : DateFormat('EEE, d MMM • h:mm a')
                                .format(event.startAt!)),
                    const SizedBox(height: 8),
                    _IconText(Icons.location_on_outlined, event.hallName),
                    const SizedBox(height: 14),
                    Align(
                        alignment: Alignment.centerRight,
                        child: Container(
                          padding: const EdgeInsets.symmetric(
                              horizontal: 14, vertical: 9),
                          decoration: BoxDecoration(
                              color: AppColors.orange,
                              borderRadius: BorderRadius.circular(30)),
                          child: Text(
                              event.startingPrice == null
                                  ? 'View details  ›'
                                  : 'From ${event.startingPrice!.toStringAsFixed(2)} ${event.currency ?? 'JOD'}',
                              style: const TextStyle(
                                  color: Colors.black,
                                  fontWeight: FontWeight.w800)),
                        )),
                  ],
                )),
          ]),
        ),
      );
}

class EventPoster extends StatelessWidget {
  const EventPoster({super.key, this.imageUrl});
  final String? imageUrl;
  @override
  Widget build(BuildContext context) {
    if (imageUrl?.isNotEmpty == true) {
      final resolved = Uri.parse(Config.baseUrl).resolve(imageUrl!).toString();
      return Image.network(resolved,
          fit: BoxFit.cover,
          errorBuilder: (_, __, ___) => const _PosterFallback());
    }
    return const _PosterFallback();
  }
}

class _PosterFallback extends StatelessWidget {
  const _PosterFallback();
  @override
  Widget build(BuildContext context) => Container(
        decoration: const BoxDecoration(
            gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFF5B231A), Color(0xFFAA401A), Color(0xFF241D31)],
        )),
        child: const Center(
            child: Icon(Icons.theater_comedy_outlined,
                size: 80, color: Color(0x88FFFFFF))),
      );
}

class _Badge extends StatelessWidget {
  const _Badge(this.text, {this.color = const Color(0xDD171719)});
  final String text;
  final Color color;
  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 6),
        decoration: BoxDecoration(
            color: color, borderRadius: BorderRadius.circular(20)),
        child: Text(text,
            style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 12)),
      );
}

class _IconText extends StatelessWidget {
  const _IconText(this.icon, this.text);
  final IconData icon;
  final String text;
  @override
  Widget build(BuildContext context) => Row(children: [
        Icon(icon, size: 17, color: AppColors.orange),
        const SizedBox(width: 8),
        Expanded(
            child: Text(text,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(color: AppColors.muted))),
      ]);
}

class _Message extends StatelessWidget {
  const _Message(this.message, {this.onRetry});
  final String message;
  final VoidCallback? onRetry;
  @override
  Widget build(BuildContext context) => Center(
          child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.event_busy_outlined,
              size: 48, color: AppColors.muted),
          const SizedBox(height: 12),
          Text(message, textAlign: TextAlign.center),
          if (onRetry != null) ...[
            const SizedBox(height: 12),
            FilledButton(onPressed: onRetry, child: const Text('Retry')),
          ],
        ],
      ));
}

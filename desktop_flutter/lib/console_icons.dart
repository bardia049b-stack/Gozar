import 'dart:math' as math;

import 'package:flutter/material.dart';

class XboxMark extends StatelessWidget {
  const XboxMark({super.key, this.size = 40});

  final double size;

  @override
  Widget build(BuildContext context) =>
      SizedBox(width: size, height: size, child: CustomPaint(painter: _XboxPainter()));
}

class _XboxPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final radius = size.width / 2;
    final center = Offset(radius, radius);
    final disc = Paint()..color = const Color(0xFF107C10);
    canvas.drawCircle(center, radius, disc);

    final ring = Paint()
      ..color = Colors.white.withOpacity(0.16)
      ..style = PaintingStyle.stroke
      ..strokeWidth = size.width * 0.045;
    canvas.drawCircle(center, radius * 0.86, ring);

    final stroke = Paint()
      ..color = Colors.white
      ..style = PaintingStyle.stroke
      ..strokeWidth = size.width * 0.12
      ..strokeCap = StrokeCap.round;
    final inset = size.width * 0.28;
    final left = Path()
      ..moveTo(inset, inset)
      ..quadraticBezierTo(size.width * 0.5, size.height * 0.42,
          size.width - inset, size.height - inset);
    final right = Path()
      ..moveTo(size.width - inset, inset)
      ..quadraticBezierTo(size.width * 0.5, size.height * 0.42, inset,
          size.height - inset);
    canvas.drawPath(left, stroke);
    canvas.drawPath(right, stroke);
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

class PlayStationMark extends StatelessWidget {
  const PlayStationMark({super.key, this.size = 40});

  final double size;

  @override
  Widget build(BuildContext context) => SizedBox(
      width: size,
      height: size,
      child: CustomPaint(painter: _PlayStationPainter()));
}

class _PlayStationPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final rounded = RRect.fromRectAndRadius(
        Rect.fromLTWH(0, 0, size.width, size.height),
        Radius.circular(size.width * 0.24));
    canvas.drawRRect(rounded, Paint()..color = const Color(0xFF003791));

    final glow = Paint()
      ..shader = const LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [Color(0x33FFFFFF), Color(0x00000000)],
      ).createShader(Rect.fromLTWH(0, 0, size.width, size.height));
    canvas.drawRRect(rounded, glow);

    final unit = size.width / 4;
    final stroke = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = size.width * 0.07
      ..strokeJoin = StrokeJoin.round;
    final fill = Paint()..style = PaintingStyle.fill;

    final triangleCenter = Offset(unit, unit);
    final triangle = Path()
      ..moveTo(triangleCenter.dx, triangleCenter.dy - unit * 0.38)
      ..lineTo(triangleCenter.dx + unit * 0.4, triangleCenter.dy + unit * 0.3)
      ..lineTo(triangleCenter.dx - unit * 0.4, triangleCenter.dy + unit * 0.3)
      ..close();
    canvas.drawPath(triangle, stroke..color = const Color(0xFF2ECC71));

    canvas.drawCircle(Offset(size.width - unit, unit), unit * 0.33,
        stroke..color = const Color(0xFFE74C3C));

    final squareCenter = Offset(unit, size.height - unit);
    canvas.drawRect(
        Rect.fromCenter(
            center: squareCenter, width: unit * 0.62, height: unit * 0.62),
        stroke..color = const Color(0xFFE056A0));

    final crossCenter = Offset(size.width - unit, size.height - unit);
    final arm = unit * 0.3;
    fill.color = const Color(0xFF4A90E2);
    final cross = Path()
      ..moveTo(crossCenter.dx - arm, crossCenter.dy - arm * 0.34)
      ..lineTo(crossCenter.dx - arm * 0.34, crossCenter.dy - arm * 0.34)
      ..lineTo(crossCenter.dx - arm * 0.34, crossCenter.dy - arm)
      ..lineTo(crossCenter.dx + arm * 0.34, crossCenter.dy - arm)
      ..lineTo(crossCenter.dx + arm * 0.34, crossCenter.dy - arm * 0.34)
      ..lineTo(crossCenter.dx + arm, crossCenter.dy - arm * 0.34)
      ..lineTo(crossCenter.dx + arm, crossCenter.dy + arm * 0.34)
      ..lineTo(crossCenter.dx + arm * 0.34, crossCenter.dy + arm * 0.34)
      ..lineTo(crossCenter.dx + arm * 0.34, crossCenter.dy + arm)
      ..lineTo(crossCenter.dx - arm * 0.34, crossCenter.dy + arm)
      ..lineTo(crossCenter.dx - arm * 0.34, crossCenter.dy + arm * 0.34)
      ..lineTo(crossCenter.dx - arm, crossCenter.dy + arm * 0.34)
      ..close();
    canvas.drawPath(cross, fill);
    canvas.save();
    canvas.translate(size.width / 2, size.height / 2);
    canvas.rotate(math.pi / 4);
    canvas.restore();
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

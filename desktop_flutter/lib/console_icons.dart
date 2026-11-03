import 'package:flutter/material.dart';

import 'theme.dart';

const _viewBox = 24.0;

class ConsoleMark extends StatelessWidget {
  const ConsoleMark({super.key, required this.xbox, this.size = 40});

  final bool xbox;
  final double size;

  @override
  Widget build(BuildContext context) => SizedBox(
        width: size,
        height: size,
        child: CustomPaint(
          painter: _MarkPainter(
            xbox ? _xboxShape() : _playStationShape(),
            mid,
          ),
        ),
      );
}

class XboxMark extends StatelessWidget {
  const XboxMark({super.key, this.size = 40});

  final double size;

  @override
  Widget build(BuildContext context) => ConsoleMark(xbox: true, size: size);
}

class PlayStationMark extends StatelessWidget {
  const PlayStationMark({super.key, this.size = 40});

  final double size;

  @override
  Widget build(BuildContext context) => ConsoleMark(xbox: false, size: size);
}

class _MarkPainter extends CustomPainter {
  _MarkPainter(this.shape, this.color);

  final Path shape;
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.save();
    canvas.scale(size.width / _viewBox, size.height / _viewBox);
    final paint = Paint()
      ..color = color
      ..isAntiAlias = true;
    canvas.drawPath(shape, paint);
    canvas.restore();
  }

  @override
  bool shouldRepaint(covariant _MarkPainter oldDelegate) =>
      oldDelegate.shape != shape || oldDelegate.color != color;
}

Path _xboxShape() {
  final path = Path();
  path.moveTo(4.102, 21.033);
  path.cubicTo(6.211, 22.881, 8.977, 24.000, 12.000, 24.000);
  path.cubicTo(15.026, 24.000, 17.789, 22.881, 19.902, 21.033);
  path.cubicTo(21.779, 19.121, 15.586, 12.324, 12.000, 9.616);
  path.cubicTo(8.418, 12.324, 2.221, 19.121, 4.102, 21.033);
  path.close();
  path.moveTo(15.262, 6.627);
  path.cubicTo(17.762, 9.588, 22.746, 16.940, 21.338, 19.539);
  path.cubicTo(23.002, 17.480, 24.000, 14.861, 24.000, 12.004);
  path.cubicTo(24.000, 8.664, 22.635, 5.642, 20.430, 3.468);
  path.cubicTo(20.430, 3.468, 20.403, 3.446, 20.348, 3.426);
  path.cubicTo(20.285, 3.404, 20.196, 3.381, 20.067, 3.381);
  path.cubicTo(19.475, 3.381, 18.082, 3.815, 15.262, 6.627);
  path.close();
  path.moveTo(3.654, 3.426);
  path.cubicTo(3.597, 3.446, 3.572, 3.467, 3.568, 3.468);
  path.cubicTo(1.365, 5.642, 0.000, 8.664, 0.000, 12.004);
  path.cubicTo(0.000, 14.858, 0.998, 17.477, 2.661, 19.537);
  path.cubicTo(1.260, 16.932, 6.240, 9.586, 8.741, 6.627);
  path.cubicTo(5.921, 3.814, 4.525, 3.382, 3.935, 3.382);
  path.cubicTo(3.804, 3.382, 3.712, 3.403, 3.654, 3.428);
  path.lineTo(3.654, 3.426);
  path.close();
  path.moveTo(12.000, 3.551);
  path.cubicTo(12.000, 3.551, 9.055, 1.828, 6.755, 1.746);
  path.cubicTo(5.852, 1.713, 5.301, 2.041, 5.234, 2.085);
  path.cubicTo(7.379, 0.646, 9.659, 0.000, 11.984, 0.000);
  path.lineTo(12.000, 0.000);
  path.cubicTo(14.334, 0.000, 16.605, 0.646, 18.766, 2.085);
  path.cubicTo(18.698, 2.039, 18.151, 1.713, 17.246, 1.746);
  path.cubicTo(14.946, 1.828, 12.000, 3.545, 12.000, 3.545);
  path.lineTo(12.000, 3.551);
  path.close();
  return path;
}


Path _playStationShape() {
  final path = Path();
  path.moveTo(8.984, 2.596);
  path.lineTo(8.984, 20.143);
  path.lineTo(12.899, 21.404);
  path.lineTo(12.899, 6.688);
  path.cubicTo(12.899, 5.998, 13.203, 5.537, 13.693, 5.697);
  path.cubicTo(14.329, 5.877, 14.453, 6.511, 14.453, 7.202);
  path.lineTo(14.453, 13.077);
  path.cubicTo(16.894, 14.270, 18.815, 13.075, 18.815, 9.925);
  path.cubicTo(18.815, 6.688, 17.689, 5.250, 14.377, 4.098);
  path.cubicTo(13.070, 3.650, 10.649, 2.912, 8.987, 2.596);
  path.close();
  path.moveTo(13.640, 18.837);
  path.lineTo(19.936, 16.562);
  path.cubicTo(20.651, 16.304, 20.762, 15.937, 20.182, 15.744);
  path.cubicTo(19.596, 15.552, 18.545, 15.605, 17.825, 15.867);
  path.lineTo(13.620, 17.367);
  path.lineTo(13.620, 14.980);
  path.lineTo(13.860, 14.895);
  path.cubicTo(13.860, 14.895, 15.061, 14.475, 16.773, 14.280);
  path.cubicTo(18.469, 14.100, 20.558, 14.310, 22.210, 14.941);
  path.cubicTo(24.058, 15.542, 24.250, 16.413, 23.786, 17.013);
  path.cubicTo(23.321, 17.613, 22.164, 18.049, 22.164, 18.049);
  path.lineTo(13.620, 21.156);
  path.lineTo(13.620, 18.860);
  path.close();
  path.moveTo(1.807, 18.600);
  path.cubicTo(-0.093, 18.055, -0.407, 16.932, 0.455, 16.280);
  path.cubicTo(1.256, 15.694, 2.615, 15.228, 2.615, 15.228);
  path.lineTo(8.230, 13.215);
  path.lineTo(8.230, 15.528);
  path.lineTo(4.205, 17.000);
  path.cubicTo(3.500, 17.271, 3.380, 17.632, 3.966, 17.826);
  path.cubicTo(4.552, 18.021, 5.603, 17.976, 6.309, 17.706);
  path.lineTo(8.247, 17.000);
  path.lineTo(8.247, 19.074);
  path.cubicTo(8.127, 19.104, 7.991, 19.118, 7.857, 19.147);
  path.cubicTo(5.918, 19.478, 3.861, 19.343, 1.819, 18.668);
  path.close();
  return path;
}

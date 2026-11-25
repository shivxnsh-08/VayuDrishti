/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        navy: { 950: '#060c18', 900: '#0b1426', 800: '#111e36', 700: '#1a2b4a', 600: '#27406a' },
      },
    },
  },
  plugins: [],
};

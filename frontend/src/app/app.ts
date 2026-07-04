import { AfterViewInit, Component, ElementRef, OnInit, inject, signal, viewChild } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Chart, registerables } from 'chart.js';
import { forkJoin } from 'rxjs';

Chart.register(...registerables);

const API = 'http://localhost:8080/api';

export interface Rate {
  fecha: string;
  moneda: string;
  compra: number | null;
  venta: number | null;
}

export interface Stats {
  minimo: number;
  maximo: number;
  promedio: number;
  variacion: number;
  observaciones: number;
}

@Component({
  selector: 'app-root',
  imports: [DecimalPipe],
  templateUrl: './app.html',
  styleUrl: './app.css'
})
export class App implements OnInit, AfterViewInit {
  private http = inject(HttpClient);
  private canvas = viewChild.required<ElementRef<HTMLCanvasElement>>('grafica');
  private chart?: Chart;

  latest = signal<Rate[]>([]);
  error = signal('');
  mensajes = signal<{ rol: 'yo' | 'ia'; texto: string }[]>([]);
  pensando = signal(false);
  stats = signal<Stats | null>(null);
  to = new Date().toISOString().slice(0, 10);
  from = new Date(Date.now() - 365 * 86400000).toISOString().slice(0, 10);

  ngOnInit() {
    this.http.get<Rate[]>(`${API}/rates/latest`).subscribe({
      next: r => this.latest.set(r),
      error: () => this.error.set('No se pudo conectar con la API. ¿Está corriendo el backend en el puerto 8080?')
    });
    this.http.get<Stats>(`${API}/rates/stats`).subscribe(s => this.stats.set(s));
  }

  ngAfterViewInit() {
    this.cargarGrafica();
  }

  cambiarRango(campo: 'from' | 'to', valor: string) {
    this[campo] = valor;
    this.cargarGrafica();
  }

  preguntar(caja: HTMLInputElement) {
    const texto = caja.value.trim();
    if (!texto || this.pensando()) return;
    caja.value = '';
    this.mensajes.update(m => [...m, { rol: 'yo', texto }]);
    this.pensando.set(true);
    this.http.post<{ respuesta: string }>(`${API}/chat`, { pregunta: texto }).subscribe({
      next: r => {
        this.mensajes.update(m => [...m, { rol: 'ia', texto: r.respuesta }]);
        this.pensando.set(false);
      },
      error: () => {
        this.mensajes.update(m => [...m, { rol: 'ia', texto: 'Error consultando la IA. ¿Están corriendo el backend y Ollama?' }]);
        this.pensando.set(false);
      }
    });
  }

  cargarGrafica() {
    forkJoin({
      serie: this.http.get<Rate[]>(`${API}/rates?moneda=USD&from=${this.from}&to=${this.to}`),
      forecast: this.http.get<Rate[]>(`${API}/rates/forecast`)
    }).subscribe(({ serie, forecast }) => {
      // la proyección solo aplica si el rango llega hasta hoy
      if (this.to < new Date().toISOString().slice(0, 10)) forecast = [];
      const proyeccion = serie.length && forecast.length
        ? [...Array(serie.length - 1).fill(null), serie[serie.length - 1].venta, ...forecast.map(d => d.venta)]
        : [];
      this.chart?.destroy();
      this.chart = new Chart(this.canvas().nativeElement, {
        type: 'line',
        data: {
          labels: [...serie.map(d => d.fecha), ...forecast.map(d => d.fecha)],
          datasets: [
            { label: 'Compra ₡', data: serie.map(d => d.compra), borderColor: '#2563eb', backgroundColor: '#2563eb', pointRadius: 0, borderWidth: 2, tension: 0.2 },
            { label: 'Venta ₡', data: serie.map(d => d.venta), borderColor: '#dc2626', backgroundColor: '#dc2626', pointRadius: 0, borderWidth: 2, tension: 0.2 },
            { label: 'Proyección ₡', data: proyeccion, borderColor: '#059669', backgroundColor: '#059669', borderDash: [6, 4], pointRadius: 0, borderWidth: 2 }
          ]
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          interaction: { mode: 'index', intersect: false },
          scales: { x: { ticks: { maxTicksLimit: 12 } } }
        }
      });
    });
  }
}
